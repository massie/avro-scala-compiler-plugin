# pom.xml #
Use the following pom file to build a maven project with the plugin. We utilize the maven-scala-plugin (http://scala-tools.org/mvnsites/maven-scala-plugin/)

```
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>your.group.id</groupId>
  <artifactId>yourartifact</artifactId>
  <version>1.0-SNAPSHOT</version>
  <name>Your Project Name</name>

  <dependencies>
    <dependency>
      <groupId>org.scala-lang</groupId>
      <artifactId>scala-library</artifactId>
      <version>2.8.0</version>
    </dependency>
    <dependency>
      <groupId>org.scala-lang</groupId>
      <artifactId>scala-compiler</artifactId>
      <version>2.8.0</version>
    </dependency>
    <dependency>
      <groupId>com.googlecode</groupId>
      <artifactId>avro-scala-compiler-plugin</artifactId>
      <version>1.1-SNAPSHOT</version>
    </dependency>
  </dependencies>
  <repositories>
    <repository>
      <id>scala-tools.org</id>
      <name>Scala-tools Maven2 Repository</name>
      <url>http://scala-tools.org/repo-releases</url>
    </repository>
    <repository>
      <id>scads.knowsql.org</id>
      <name>SCADS Maven2 Repository</name>
      <url>http://scads.knowsql.org/nexus/content/groups/public</url>
    </repository>
  </repositories>
  <pluginRepositories>
    <pluginRepository>
      <id>scala-tools.org</id>
      <name>Scala-tools Maven2 Repository</name>
      <url>http://scala-tools.org/repo-releases</url>
    </pluginRepository>
    <pluginRepository>
      <id>scads.knowsql.org</id>
      <name>SCADS Maven2 Repository</name>
      <url>http://scads.knowsql.org/nexus/content/groups/public</url>
    </pluginRepository>
  </pluginRepositories>
  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.scala-tools</groupId>
          <artifactId>maven-scala-plugin</artifactId>
          <version>2.14.1</version>
        </plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.scala-tools</groupId>
        <artifactId>maven-scala-plugin</artifactId>
        <configuration>
          <!-- see documentation for maven-scala-plugin for more configuration options -->
          <compilerPlugins>
            <compilerPlugin>
              <groupId>com.googlecode</groupId>
              <artifactId>avro-scala-compiler-plugin</artifactId>
              <version>1.1-SNAPSHOT</version>
            </compilerPlugin>
          </compilerPlugins>
          <!-- for now, have to explicitly specify compiler plugin dependencies below -->
          <dependencies>
            <dependency>
              <groupId>org.apache.hadoop</groupId>
              <artifactId>avro</artifactId>
              <version>1.3.3</version>
            </dependency>
            <dependency>
              <groupId>org.codehaus.jackson</groupId>
              <artifactId>jackson-mapper-asl</artifactId>
              <version>1.4.2</version>
            </dependency>
          </dependencies>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>compile</goal>
              <goal>testCompile</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```