maven-s3-wagon
==============

Maven Wagon to read from S3 Maven repositories and write to them with private permissions.

This project is based on the [Kuali S3 Wagon][kuali], but deploys using private permissions. This is particularly helpful for closed-source projects.

Maven
=====

    <build>
    ...
        <extensions>
            <extension>
                <groupId>com.allogy.maven.wagon</groupId>
                <artifactId>maven-s3-wagon</artifactId>
                <version>1.0</version>
            </extension>
        </extensions>
    ...
    </build>


[kuali]: https://github.com/jcaddel/maven-s3-wagon
