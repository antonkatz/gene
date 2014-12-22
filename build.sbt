name := "gene_reviews"

version := "1.0"

scalaVersion := "2.11.4"

libraryDependencies ++=
  Seq("net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    "org.slf4j" % "slf4j-api" % "1.7.7",
    "org.slf4j" % "slf4j-simple" % "1.7.7",
    "com.typesafe.play" %% "play-json" % "2.3.4")

resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"
