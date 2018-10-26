package jp.co.soramitsu.nakayoshi

import jp.co.soramitsu.nakayoshi.internals.TelegramBot

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import info.mukel.telegrambot4s.api.Polling
import info.mukel.telegrambot4s.methods.{GetFile, ParseMode, SendMessage}
import info.mukel.telegrambot4s.models.{ChatType, Message, MessageEntity, User}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class BotTg(val token: String, val admins: Set[String])
           (implicit val system: ActorSystem,
            implicit val materializer: ActorMaterializer)
  extends Actor with TelegramBot with Polling {

  private var router: ActorRef = _
  implicit val timeout: Timeout = Timeout(2, SECONDS)
  lazy implicit val executionContext: ExecutionContext = system.dispatcher

  private def save(srcPath: String, localName: String): Future[Unit] = {
    import java.io.File
    import java.net.URL
    import scala.sys.process._

    val url = new URL(s"https://$host/file/bot$token/$srcPath")
    val file = new File(Strings.publicPath + localName)
    file.getParentFile.mkdirs()
    Future { url #> file !! }
  }

  private def download(fileId: String): Future[String] = {
    import java.nio.file.Paths
    def getFilename(s: String) = Paths.get(s).getFileName.toString

    Storage.getFileAddress(fileId).flatMap {
      case Some(e) => Future.successful(e)
      case None =>
        for {
          file <- request(GetFile(fileId))
          path <- Storage.insertFile(fileId, file.filePath.fold("")(getFilename))
          _ <- save(file.filePath.get, path)
        } yield path
    }.andThen { case Failure(e) => l.error("Failed to save file from Telegram", e) }
  }

  adminCmd('help) { implicit msg: Message =>
    reply("*Available commands:*\n" +
      "/help - this help message\n" +
      "/gitter\\_chats - show all available Gitter rooms\n" +
      "/telegram\\_chats - show Telegram chats (may include unavailable chats)\n" +
      "/rocketchat\\_chats - show Rocketchat chats\n" +
      "/connections - show all connections\n" +
      "/connect <telegram\\_chat\\_id> <gitter\\_room\\_id> <rocketchat\\_channel\\_id> - add a new connection (`none` instead of ID is fine)",
      parseMode = Some(ParseMode.Markdown))
  }

  adminCmd('gitter_chats) { implicit msg: Message =>
    (router ? 'getGtChats).mapTo[List[GitterRoom]].onComplete {
      case Success(list) =>
        val message = list.map { room =>
          s"[${room.name}](https://gitter.im${room.url})\nID `${room.id}`\n\n"
        }.foldLeft("*Available Gitter rooms*\n\n")(_ + _)
        reply(message, parseMode = Some(ParseMode.Markdown))
      case Failure(e) =>
        l.error("Failed to respond to a command with a list of Gitter chats", e)
        reply("Gitter chat list unavailable")
    }
  }

  adminCmd('telegram_chats) { implicit msg: Message =>
    (router ? 'getTgChats).mapTo[Seq[(Long, TelegramChat)]].onComplete {
      case Success(list) =>
        val message = list.map { case (id, chat) =>
          val title = chat.title
          val username = chat.username.fold("")(it => s" (@$it)")
          s"$title$username\nID <code>$id</code>\n\n"
        }.foldLeft("<b>Available Telegram chats</b>\n\n")(_ + _)
        reply(message, parseMode = Some(ParseMode.HTML))
      case Failure(e) =>
        l.error("Failed to respond to a command with a list of Telegram chats", e)
        reply("Telegram chat list unavailable")
    }
  }

  adminCmd('rocketchat_chats) { implicit msg: Message =>
    (router ? 'getRcChats).mapTo[Map[String, String]].onComplete {
      case Success(list) =>
        val message = list.map { case (id, name) => s"#$name <code>$id</code>\n\n"}
          .foldLeft("<b>Available Rocketchat chats</b>\n\n")(_ + _)
        reply(message, parseMode = Some(ParseMode.HTML))
      case Failure(e) =>
        l.error("Failed to respond to a command with a list of Rocketchat chats", e)
        reply("Rocketchat chat list unavailable")
    }
  }

  adminCmd('connections) { implicit msg: Message =>
    (router ? 'getConns).mapTo[Seq[Connection]].onComplete {
      case Success(list) =>
        val message = list.map { conn =>
            s"${conn.tgId.getOrElse("_none_")}; " +
            s"${conn.gtId.getOrElse("_none_")}; " +
            s"${conn.rcId.getOrElse("_none_")}\n"
          }.foldLeft("*Connections (Telegram; Gitter; Rocketchat)*\n\n")(_ + _)
        reply(message, parseMode = Some(ParseMode.Markdown))
      case Failure(e) =>
        l.error("Failed to respond to a command with a list of connections", e)
        reply("Connections list unavailable")
    }
  }

  adminCmd('connect) { implicit msg: Message =>
    val tokens = msg.text.get.split(' ')
    if(tokens.length != 4) {
      reply("*Usage:* /connect <telegram\\_chat\\_id> <gitter\\_room\\_id> <rocketchat\\_channel\\_id>\n" +
        "Type `none` instead of ID if there is no room to connect", parseMode = Some(ParseMode.Markdown))
    } else {
      val tgRoom = try Some(tokens(1).toLong) catch { case e: NumberFormatException => None }
      val gtRoom = if (tokens(2) != "none") Some(tokens(1)) else None
      val rcRoom = if (tokens(3) != "none") Some(tokens(3)) else None

      if (Seq(gtRoom, tgRoom, rcRoom).flatten.lengthCompare(2) < 0)
        reply("At least two chat rooms must be specified")
      else {
        router ! MsgConnect(Connection(tgRoom, gtRoom, rcRoom))
        reply("Added a new connection")
      }
    }
  }

  adminCmd('rocketchat_join) { implicit msg: Message =>
    val tokens = msg.text.get.split(' ')
    if(tokens.length != 2) {
      reply("*Usage:* /rocketchat_join <group_name>\n", parseMode = Some(ParseMode.Markdown))
    } else {
      (router ? MsgRcJoin(tokens(1))).mapTo[Future[Unit]].onComplete {
        case Success(_) =>
          val text = s"Joined Rocketchat chat ${tokens(1)}"
          l.info(text)
          reply(text)
        case Failure(e) =>
          val text = s"Failed to join Rocketchat chat ${tokens(1)}"
          l.info(text, e)
          reply(text)
      }
    }
  }

  onMessage { implicit msg: Message =>
    if(msg.chat.`type` == ChatType.Supergroup) {
      val user = msg.from.get
      router ! MsgAddTgChat(msg.chat.id, TelegramChat(msg.chat.title.get, msg.chat.username))
      l.info(s"Received message from chat ${msg.chat.id}")
      // Save photo or sticker locally and get its local url
      val photoUrl =
        msg.photo.map(_.maxBy(_.width).fileId)
          .orElse(msg.sticker.map(_.fileId))
          .map(download)

      // Forward message to the router
      def userConvert(user: User) = user.firstName + user.lastName.fold("")(' ' + _)

      val userName = userConvert(user)
      val forward =
        msg.forwardFrom.map(userConvert)
          .orElse(msg.forwardFromChat.flatMap(_.title))
          .map("forwarded from " + _)
      val message: Option[(String, Seq[MessageEntity])] =
        msg.text.map((_, msg.entities.getOrElse(Seq())))
          .orElse(msg.caption.map((_, Seq())))
      val alias = user.username

      photoUrl match {
        case None =>
          router ! MsgFromTelegram(msg.chat.id, msg.messageId, userName, alias, message, None, forward)
        case Some(future) =>
          future.foreach { url =>
            router ! MsgFromTelegram(msg.chat.id, msg.messageId, userName, alias, message, Some(url), forward)
          }
      }
    }
  }

  override def receive: Receive = {
    case MsgRun(refRouter) =>
      router = refRouter
      run()
    case m @ MsgSendTelegram(id, msg) =>
      l.info(s"Sending Telegram message: $m")
      request(SendMessage(id, msg, parseMode = Some(ParseMode.Markdown), disableWebPagePreview = Some(true)))
        .map(_.messageId) pipeTo sender()
  }
}
