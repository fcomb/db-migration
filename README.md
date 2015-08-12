# Lightweight DB Migration

[![Build Status](https://travis-ci.org/fcomb/db-migration.svg?branch=develop)](https://travis-ci.org/fcomb/db-migration)
[![License](https://img.shields.io/:license-MIT-green.svg)](http://opensource.org/licenses/MIT)

Tested on PostgreSQL 9.4.

## TODO

* Locks
* Tests

## Add to project

### Add resolvers to your `build.sbt`

```scala
resolvers += Resolver.url("fcomb", url("https://dl.bintray.com/fcomb/maven"))(Resolver.ivyStylePatterns)
```

### Add dependencies to your `build.sbt`

```scala
libraryDependencies += "io.fcomb" %% "db-migration" % "0.1.0"
```

## Usage

Add migrations as a plain sql files into `src/main/resources/sql/migrations` with name format `V{\d+}__{\w+}.sql` (`V1427135578__CreateUsers.sql` for example).

Then apply your migrations:

```scala
import io.fcomb.db.Migration

Migration.run("jdbc:postgresql://127.0.0.1:5432/fcomb", "postgres", "")
```
