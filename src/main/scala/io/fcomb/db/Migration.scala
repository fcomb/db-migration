package io.fcomb.db

import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.sql.{DriverManager, SQLException}
import java.time.LocalDateTime
import java.util.jar._
import java.util.Properties
import org.slf4j.LoggerFactory
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.util.Try

final case class MigrationItemOptions(
    runInTransaction: Boolean = true
) {
  def serialize() =
    Map("runInTransaction" -> runInTransaction).map { case (k, v) => s"$k:$v" }.mkString(";")
}

object MigrationItemOptions {
  def deserialize(s: String) = {
    val optsMap = s
      .split(';')
      .map(_.split(':').toList)
      .collect { case k :: v :: Nil => (k, v) }
      .toMap
    val runInTransaction = optsMap.get("runInTransaction") match {
      case Some("false") => false
      case _             => true
    }
    MigrationItemOptions(
      runInTransaction = runInTransaction
    )
  }
}

final case class MigrationItem(
    version: BigInt,
    name: String,
    body: String,
    sha1: String,
    appliedAt: LocalDateTime,
    options: MigrationItemOptions
)

final class Migration(
    url: String,
    user: String,
    password: String,
    migrationsPath: String,
    schema: String
) {
  private val logger = LoggerFactory.getLogger(this.getClass)

  private val migrationSchemaSql = s"""
    CREATE TABLE IF NOT EXISTS $schema.migrations (
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

  def unsafeRun(): Unit = {
    val connection     = getConnection()
    val lockConnection = getConnection()
    lockConnection.setAutoCommit(false)
    try {
      lockConnection.prepareCall(migrationSchemaSql).executeUpdate()
      logger.debug("Locking migrations table")
      val rs =
        lockConnection.prepareCall(s"SELECT * FROM $schema.migrations FOR UPDATE").executeQuery()
      val persistMigrations = new ListBuffer[MigrationItem]()
      while (rs.next) {
        persistMigrations += MigrationItem(
          version = BigInt(rs.getBigDecimal("version").toBigInteger),
          name = rs.getString("name"),
          body = rs.getString("body"),
          sha1 = rs.getString("sha1"),
          appliedAt = rs.getTimestamp("applied_at").toLocalDateTime(),
          options = MigrationItemOptions.deserialize(rs.getString("options"))
        )
      }
      lockConnection.setSavepoint()
      val persistVersionMap =
        persistMigrations.map(m => (m.version, m)).toMap

      getMigrations(getClassLoader).foreach { m =>
        persistVersionMap.get(m.version) match {
          case Some(pm) =>
            require(
              pm.sha1 == m.sha1,
              s"Migration '${pm.version}' has changed ${pm.sha1} != ${m.sha1}!\nDiff: ${pm.body.toSeq
                .diff(m.body)}"
            )
          case None =>
            try {
              logger.debug(s"Applying the migration ${m.version}#${m.name}: ${m.body}")

              connection.setAutoCommit(!m.options.runInTransaction)
              val bodyStmt = connection.prepareStatement(m.body)
              bodyStmt.execute()
              if (m.options.runInTransaction) connection.commit()
              bodyStmt.close()

              val mStmt = lockConnection.prepareStatement(s"""
                    INSERT INTO $schema.migrations (version, name, body, sha1, applied_at, options)
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
                logger.error(s"Problem migration ${m.version}#${m.name}: ${m.body}")
                throw e
            }
        }
      }
    } finally {
      try lockConnection.commit()
      finally {
        logger.debug("Unlocking migrations table")
        lockConnection.close()
      }
      connection.close()
    }
  }

  def unsafeClean(schema: String = "public"): Unit = {
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
    } finally connection.close()
    ()
  }

  private def getClassLoader() =
    Try(Thread.currentThread.getClass.getClassLoader).toOption
      .flatMap(Option(_))
      .getOrElse(this.getClass.getClassLoader)

  private def getMigrationFiles(loader: ClassLoader) = {
    val migrationFormat = "(\\A|\\/)V(\\d+)\\_{2}(\\w+)\\.sql\\z".r
    val files = Option(loader.getResources(migrationsPath))
      .map(_.asScala.toList.flatMap { url =>
        url.getProtocol match {
          case "file" => new File(url.toURI).listFiles.map(_.getName).toList
          case "jar" =>
            val jarPath = url.getPath.drop(5).takeWhile(_ != '!')
            val jarFile = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))
            jarFile.entries.asScala.map(_.getName).filter(_.startsWith(migrationsPath)).toList
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

  private def getMigrations(loader: ClassLoader) = {
    val crypt = MessageDigest.getInstance("SHA-1")
    getMigrationFiles(loader).map {
      case (version, name) =>
        val migrationName = s"V${version}__$name"
        val stream        = loader.getResourceAsStream(s"$migrationsPath/$migrationName.sql")
        val rawMigration  = Source.fromInputStream(stream)("UTF-8").getLines.toList
        val options = rawMigration.headOption match {
          case Some(s) if s.startsWith("--") =>
            val args = s.dropWhile(_ == '-').split("\\s+").map(_.trim)
            args.foldLeft(MigrationItemOptions()) { (options, o) =>
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
          case _ => MigrationItemOptions()
        }
        val body = rawMigration
          .map(
            _.replaceFirst("--.*", "")
              .replaceFirst("\\A(\\s|\\n)+", "")
              .replaceFirst("(\\s|\\n)+\\z", "")
              .replaceAll("\\s{2,}", " ")
          )
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

  def unsafeRun(
      url: String,
      user: String,
      password: String,
      migrationsPath: String = Migration.defaultMigrationsPath,
      schema: String = "public"
  ): Unit =
    new Migration(url, user, password, migrationsPath, schema).unsafeRun()

  def unsafeClean(
      url: String,
      user: String,
      password: String,
      schema: String = "public"
  ): Unit =
    new Migration(
      url,
      user,
      password,
      migrationsPath = Migration.defaultMigrationsPath,
      schema = schema
    ).unsafeClean()
}
