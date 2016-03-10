name := "lasso"

version := "1.0"

scalaVersion := "2.11.4"

organization := "himrod"

libraryDependencies += "org.apache.spark" %% "spark-core" % "1.5.2" % "provided"

//libraryDependencies += "org.apache.spark" %% "spark-mllib" % "1.5.2" % "provided"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.0" % "test"

libraryDependencies += "org.scalanlp" %% "breeze" % "0.12"

libraryDependencies += "org.scalanlp" %% "breeze-natives" % "0.12"

libraryDependencies += "com.github.fommil.netlib" % "all" % "1.1.2"

libraryDependencies += "org.jblas" % "jblas" % "1.2.3"

//libraryDependencies += "org.apache.commons" % "commons-math3" % "3.0"

resolvers += "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases"

