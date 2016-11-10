package io.fcomb.db

import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.sql.{DriverManager, SQLException}
import java.time.LocalDateTime
import java.util.Properties
import java.util.jar._
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.io.Source

case class MigrationItemOptions(
    runInTransaction: Boolean = true
) {
  def serialize() =
    Map("runInTransaction" -> runInTransaction).map { case (k, v) => s"$k:$v" }
      .mkString(";")
}

object MigrationItemOptions {
  def deserialize(s: String) = {
    val optsMap = s
      .split(';')
      .map(_.split(':').toList)
      .collect {
        case k :: v :: Nil => (k, v)
      }
      .toMap
    val runInTransaction = optsMap.get("runInTransaction") match {
      case Some("false") => false
      case _ => true
    }
    MigrationItemOptions(
      runInTransaction = runInTransaction
    )
  }
}

case class MigrationItem(
    version: BigInt,
    name: String,
    body: String,
    sha1: String,
    appliedAt: LocalDateTime,
    options: MigrationItemOptions
)

class Migration(
    url: String,
    user: String,
    password: String,
    migrationsPath: String = Migration.defaultMigrationsPath
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private val migrationSchemaSql = """
    CREATE TABLE IF NOT EXISTS migrations (
      version bigint,
      name varchar(255) NOT NULL,
      body text NOT NULL,
      sha1 char(40) NOT NULL,
      applied_at TIMESTAMP NOT NULL,
      options text NOT NULL,
      PRIMARY KEY (version)
    );
    """

  private def getConnection() = {
    val props = new Properties()
    props.setProperty("user", user)
    props.setProperty("password", password)
    DriverManager.getConnection(url, props)
  }

  def run()(implicit ec: ExecutionContext) =
    Future {
      blocking {
        val connection = getConnection()
        val lockConnection = getConnection()
        try {
          lockConnection.prepareCall(migrationSchemaSql).executeUpdate()
          lockConnection.setAutoCommit(false)
          logger.info("Locking migrations table")
          val rs = lockConnection
            .prepareCall("SELECT * FROM migrations FOR UPDATE")
            .executeQuery()
          val count = rs.getMetaData().getColumnCount()
          val persistMigrations = new ListBuffer[MigrationItem]()
          while (rs.next) {
            persistMigrations += MigrationItem(
              version = BigInt(rs.getBigDecimal("version").toBigInteger),
              name = rs.getString("name"),
              body = rs.getString("body"),
              sha1 = rs.getString("sha1"),
              appliedAt = rs.getTimestamp("applied_at").toLocalDateTime(),
              options =
                MigrationItemOptions.deserialize(rs.getString("options"))
            )
          }
          val persistVersionMap =
            persistMigrations.map(m => (m.version, m)).toMap

          getMigrations.foreach { m =>
            persistVersionMap.get(m.version) match {
              case Some(pm) =>
                require(
                  pm.sha1 == m.sha1,
                  s"Migration '${pm.version}' has changed ${pm.sha1} != ${m.sha1}!\nDiff: ${pm.body
                    .diff(m.body)}"
                )
              case None =>
                try {
                  logger.info(
                    s"Applying the migration ${m.version}#${m.name}: ${m.body}")

                  connection.setAutoCommit(!m.options.runInTransaction)
                  val bodyStmt = connection.prepareStatement(m.body)
                  bodyStmt.execute()
                  if (m.options.runInTransaction) connection.commit()
                  bodyStmt.close()

                  val mStmt = lockConnection.prepareStatement(
                    """
                    INSERT INTO migrations (version, name, body, sha1, applied_at, options)
                    VALUES (?, ?, ?, ?, now(), ?)
                    """)
                  mStmt.setBigDecimal(1, BigDecimal(m.version).bigDecimal)
                  mStmt.setString(2, m.name)
                  mStmt.setString(3, m.body)
                  mStmt.setString(4, m.sha1)
                  mStmt.setString(5, m.options.serialize)
                  mStmt.executeUpdate()
                  mStmt.close()
                } catch {
                  case e: SQLException =>
                    if (m.options.runInTransaction) connection.rollback()
                    logger.error(
                      s"Problem migration ${m.version}#${m.name}: ${m.body}")
                    throw e
                }
            }
          }
        } finally {
          try {
            lockConnection.commit()
          } finally {
            logger.info("Unlocking migrations table")
            lockConnection.close()
          }
          connection.close()
        }
      }
    }

  def clean(schema: String = "public")(implicit ec: ExecutionContext) = {
    Future {
      blocking {
        val connection = getConnection()
        try {
          connection
            .prepareCall(
              s"""
            DROP SCHEMA $schema cascade;
            CREATE SCHEMA $schema;
            """
            )
            .executeUpdate()
        } finally {
          connection.close()
        }
      }
    }
  }

  private def getKlassLoader() =
    Option(Thread.currentThread).getOrElse(this).getClass.getClassLoader

  private def getMigrationFiles() = {
    val migrationFormat = "(\\A|\\/)V(\\d+)\\_{2}(\\w+)\\.sql\\z".r
    val files = Option(getKlassLoader.getResources(migrationsPath))
      .map(_.toList.flatMap { url =>
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
      })
      .getOrElse(List.empty)
    files
      .map(migrationFormat.findFirstMatchIn)
      .collect {
        case Some(m) => (BigInt(m.group(2)), m.group(3))
      }
      .sortBy(_._1)
  }

  private def getMigrations() = {
    val crypt = MessageDigest.getInstance("SHA-1")
    getMigrationFiles().map {
      case (version, name) =>
        val migrationName = s"V${version}__$name"
        val rawMigration = Source
          .fromURL(
            getKlassLoader.getResource(s"$migrationsPath/$migrationName.sql"))
          .getLines
          .toList
        val options = rawMigration.headOption match {
          case Some(s) if s.startsWith("--") =>
            val args = s.dropWhile(_ == '-').split("\\s+").map(_.trim)
            args.foldLeft(MigrationItemOptions()) { (options, o) =>
              o match {
                case _ if o.startsWith("runInTransaction") =>
                  val v = o.split(':').last.toLowerCase match {
                    case "true" => true
                    case "false" => false
                  }
                  options.copy(runInTransaction = v)
                case _ => options
              }
            }
          case _ => MigrationItemOptions()
        }
        val body = rawMigration
          .map(
            _.replaceFirst("--.*", "")
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
        MigrationItem(
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

object Migration {
  val defaultMigrationsPath = "sql/migrations"

  def run(
      url: String,
      user: String,
      password: String,
      migrationsPath: String = Migration.defaultMigrationsPath
  )(implicit ec: ExecutionContext) =
    new Migration(url, user, password, migrationsPath).run()

  def clean(
      url: String,
      user: String,
      password: String
  )(implicit ec: ExecutionContext) =
    new Migration(url, user, password).clean()
}
