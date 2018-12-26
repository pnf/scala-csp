name := "scala-csp"

version := "1.0"

scalaVersion := "2.12.8"

libraryDependencies += "org.scala-lang.modules" %% "scala-async" % "0.9.7"

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.25"

libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.25"

libraryDependencies += "junit" % "junit" % "4.12"

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"

libraryDependencies += "com.typesafe.akka" % "akka-stream-experimental_2.11" % "1.0-RC2"
