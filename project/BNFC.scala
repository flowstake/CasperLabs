import sbt._
import Keys._
import scala.sys.process._
import java.io.File
import java.nio.file.{Files, StandardCopyOption}

object BNFC {

  lazy val BNFCConfig     = config("bnfc")
  lazy val bnfcNamespace  = settingKey[String]("Namespace to prepend to the package/module name")
  lazy val bnfcGrammarDir = settingKey[File]("Directory for BNFC grammar files")
  lazy val bnfcOutputDir  = settingKey[File]("Directory for Java files generated by BNFC")
  lazy val bnfcDocDir     = settingKey[File]("Directory for LaTeX files generated by BNFC")
  lazy val generate       = taskKey[Unit]("Generates Java files from BNFC grammar files")
  lazy val cleanDocs      = taskKey[Unit]("Cleans BNFC-generated LaTeX files")
  lazy val generateDocs   = taskKey[Unit]("Generates LaTeX files from BNFC grammar files")

  def cleanDir(path: File): Unit =
    if (path.exists) {
      if (path.isDirectory) {
        path.listFiles.foreach(f => cleanDir(f))
      }
      if (!path.delete) {
        throw new Error(s"Failed to delete $path")
      }
    }

  def moveFile(source: String, target: String): Unit = {
    val srcPath    = new File(source).toPath
    val targetPath = new File(target).toPath
    Files.move(srcPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
  }

  def nsToPath(ns: String): String =
    ns.replaceAll("\\.", "/")

  def stripSuffix(filename: String): String =
    filename.split("\\.").head

  def makeOutputPath(grammarFile: File, outputDir: File, namespace: String): String =
    s"$outputDir/${nsToPath(namespace)}/${stripSuffix(grammarFile.getName)}"

  def process(cmd: String): Unit = {
    println(s"sys call: $cmd")
    val res = Process(cmd).!
    if (res != 0)
      throw new Error(s"sys call failed: $cmd finished with $res")
  }

  def bnfcGenerateSources(
      fullClasspath: Seq[Attributed[File]],
      grammarFile: File,
      outputDir: File,
      namespace: String
  ): Unit = {
    val targPath: String = makeOutputPath(grammarFile, outputDir, namespace)
    val bnfcCmd: String =
      s"bnfc -l --java --jflex -o ${outputDir.getAbsolutePath} -p $namespace $grammarFile"

    val (classPathSeparator: String, jlexCmd: String) =
      Detector.detect(Seq("fedora")).osName match {
        case "windows" => (";", s"jflex.bat $targPath/Yylex")
        case _         => (":", s"jflex $targPath/Yylex")
      }

    val classpath: String = fullClasspath.map(e => e.data).mkString(classPathSeparator)

    val cupCmd: String =
      s"java -cp $classpath java_cup.Main -locations -expect 100 $targPath/${stripSuffix(grammarFile.getName)}.cup" // TODO: Figure out naming behind _cup.cup

    process(bnfcCmd)
    process(jlexCmd)
    //renaming default _cup
    moveFile(s"$targPath/_cup.cup", s"$targPath/${stripSuffix(grammarFile.getName)}.cup")

    process(cupCmd)

    moveFile("sym.java", s"$targPath/sym.java")
    moveFile("parser.java", s"$targPath/parser.java")
  }

  def bnfcGenerateLaTeX(grammarFile: File, outputDir: File): Unit = {
    val bnfcCmd: String = s"bnfc --latex -o ${outputDir.getAbsolutePath} $grammarFile"
    Process(bnfcCmd) !
  }

  def bnfcFiles(base: File): Seq[File] = (base * "*.cf").get

  lazy val bnfcSettings = {
    inConfig(BNFCConfig)(
      Defaults.configSettings ++ Seq(
        // format: off
        javaSource     := (javaSource in Compile).value,
        scalaSource    := (javaSource in Compile).value,
        bnfcNamespace  := "coop.rchain.rholang.syntax",
        bnfcGrammarDir := baseDirectory.value / "src" / "main" / "bnfc",
        bnfcOutputDir  := (javaSource in Compile).value,
        bnfcDocDir     := baseDirectory.value / "doc" / "bnfc",
        clean          := cleanDir(bnfcOutputDir.value / nsToPath(bnfcNamespace.value)),
        generate       := {
          val fullCP = (fullClasspath in BNFCConfig).value
          bnfcFiles(bnfcGrammarDir.value).foreach { (f: File) =>
            bnfcGenerateSources(fullCP, f, bnfcOutputDir.value, bnfcNamespace.value)
          }
        },
        cleanDocs      := cleanDir(bnfcDocDir.value),
        generateDocs   := bnfcFiles(bnfcGrammarDir.value).foreach { (f: File) =>
          bnfcGenerateLaTeX(f, bnfcDocDir.value)
        }
        // format: on
      )
    )
  }
}
