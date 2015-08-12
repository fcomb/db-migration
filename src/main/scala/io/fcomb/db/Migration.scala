package io.fcomb.db

import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.sql.{ DriverManager, SQLException }
import java.time.LocalDateTime
import java.util.Properties
import java.util.jar._
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.io.Source

object Migration {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private val migrationSchemaSql = """
    CREATE TABLE IF NOT EXISTS migrations (
      version integer,
      name varchar(255) NOT NULL,
      body text NOT NULL,
      sha1 char(40) NOT NULL,
      applied_at TIMESTAMP NOT NULL,
      options text NOT NULL,
      PRIMARY KEY (version)
    );
    """

  private case class MigrationOptions(
      runInTransaction: Boolean = true
  ) {
    def serialize() =
      Map("runInTransaction" -> runInTransaction)
        .map { case (k, v) => s"$k:$v" }
        .mkString(";")
  }

  private object MigrationOptions {
    def deserialize(s: String) = {
      val optsMap = s.split(';')
        .map(_.split(':').toList match {
          case k :: v :: Nil => (k, v)
        })
        .toMap
      val runInTransaction = optsMap.get("runInTransaction") match {
        case Some("false") => false
        case _             => true
      }
      MigrationOptions(
        runInTransaction = runInTransaction
      )
    }
  }

  private case class Migration(
    version:   Int,
    name:      String,
    body:      String,
    sha1:      String,
    appliedAt: LocalDateTime,
    options:   MigrationOptions
  )

  def run(
    url:            String,
    user:           String,
    password:       String,
    migrationsPath: String = defaultMigrationsPath
  )(implicit ec: ExecutionContext) =
    Future {
      blocking {
        val props = new Properties()
        props.setProperty("user", user)
        props.setProperty("password", password)
        val connection = DriverManager.getConnection(url, props)
        try {
          connection.prepareCall(migrationSchemaSql).executeUpdate()

          val rs = connection
            .prepareCall("SELECT * FROM migrations")
            .executeQuery()
          val count = rs.getMetaData().getColumnCount()
          val persistMigrations = new ListBuffer[Migration]()
          while (rs.next) {
            val migration = Migration(
              version = rs.getInt("version"),
              name = rs.getString("name"),
              body = rs.getString("body"),
              sha1 = rs.getString("sha1"),
              appliedAt = rs.getTimestamp("applied_at").toLocalDateTime(),
              options = MigrationOptions.deserialize(rs.getString("options"))
            )
            persistMigrations += migration
          }
          val persistVersionMap = persistMigrations.map(m => (m.version, m)).toMap

          getMigrations(migrationsPath).foreach { m =>
            persistVersionMap.get(m.version) match {
              case Some(pm) => require(
                pm.sha1 == m.sha1,
                s"Migration '${pm.version}' has changed ${pm.sha1} != ${m.sha1}!\nDiff: ${pm.body.diff(m.body)}"
              )
              case None =>
                try {
                  connection.setAutoCommit(!m.options.runInTransaction)
                  val body = connection.prepareStatement(m.body)
                  body.execute()
                  body.close()

                  connection.setAutoCommit(false)
                  val ps = connection.prepareStatement("""
                    INSERT INTO migrations (version, name, body, sha1, applied_at, options)
                    VALUES (?, ?, ?, ?, now(), ?)
                    """)
                  ps.setInt(1, m.version)
                  ps.setString(2, m.name)
                  ps.setString(3, m.body)
                  ps.setString(4, m.sha1)
                  ps.setString(5, m.options.serialize)
                  ps.executeUpdate()
                  ps.close()
                  connection.commit()
                } catch {
                  case e: SQLException =>
                    if (m.options.runInTransaction) connection.rollback()
                    logger.error(s"Problem migration ${m.version}#${m.name}: ${m.body}")
                    throw e
                }
            }
          }
        } finally {
          connection.close()
        }
      }
    }

  private def getKlassLoader() =
    Option(Thread.currentThread).getOrElse(this).getClass.getClassLoader

  private val defaultMigrationsPath = "sql/migrations"

  private def getMigrationFiles(migrationsPath: String) = {
    val migrationFormat = "(\\A|\\/)V(\\d+)\\_{2}(\\w+)\\.sql\\z".r
    val files = Option(getKlassLoader.getResource(migrationsPath)).map { url =>
      url.getProtocol match {
        case "file" => new File(url.toURI).listFiles.map(_.getName).toList
        case "jar" =>
          val jarPath = url.getPath.drop(5).takeWhile(_ != '!')
          val jarFile = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))
          jarFile.entries
            .map(_.getName)
            .filter(_.startsWith(migrationsPath))
            .toList
      }
    }.getOrElse(List.empty)
    files
      .map(migrationFormat.findFirstMatchIn)
      .collect {
        case Some(m) => (m.group(2).toInt, m.group(3))
      }
      .sortBy(_._1)
  }

  private def getMigrations(migrationsPath: String) = {
    val crypt = MessageDigest.getInstance("SHA-1")
    getMigrationFiles(migrationsPath).map {
      case (version, name) =>
        val migrationName = s"V${version}__$name"
        val rawMigration = Source
          .fromURL(getKlassLoader.getResource(s"$migrationsPath/$migrationName.sql"))
          .getLines
          .toList
        val options = rawMigration.headOption match {
          case Some(s) if s.startsWith("--") =>
            val args = s.dropWhile(_ == '-').split("\\s+").map(_.trim)
            args.foldLeft(MigrationOptions()) { (options, o) =>
              o match {
                case _ if o.startsWith("runInTransaction") =>
                  val v = o.split(':').last.toLowerCase match {
                    case "true"  => true
                    case "false" => false
                  }
                  options.copy(runInTransaction = v)
                case _ => options
              }
            }
          case _ => MigrationOptions()
        }
        val body = rawMigration
          .map(_.replaceFirst("--.*", "")
            .replaceFirst("\\A(\\s|\\n)+", "")
            .replaceFirst("(\\s|\\n)+\\z", "")
            .replaceAll("\\s{2,}", " "))
          .filter(_.nonEmpty)
          .mkString(" ")
          .replaceAll("(?s)/\\*[^\\*]*\\*\\/", "")
        require(body.nonEmpty, s"Migration $migrationName is empty")

        crypt.reset()
        crypt.update(body.getBytes("UTF-8"))
        val sha1 = crypt.digest().map("%02X".format(_)).mkString.toLowerCase
        Migration(
          version = version,
          body = body,
          name = name,
          sha1 = sha1,
          appliedAt = LocalDateTime.now,
          options = options
        )
    }
  }
}
