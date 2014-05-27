import _root_.sbt.Keys._
import play.PlayScala

name := "play-iteratee-stackoverflow"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  ws,
  "org.scalatestplus" %% "play" % "1.1.0-RC1" % "test"
)

lazy val root = (project in file(".")).enablePlugins(PlayScala)
  .settings(scalacOptions ++= Seq("-language:reflectiveCalls", "-feature"))