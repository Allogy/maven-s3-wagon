maven-s3-wagon
==============

A Maven [Wagon](https://maven.apache.org/wagon/) that reads from and writes to
S3-backed Maven repositories, built on **AWS SDK for Java v2**.

Version 2.0.0 is a ground-up reimplementation on `AbstractWagon` +
`software.amazon.awssdk` that authenticates through the **AWS default
credential chain** — environment variables, SSO, profile `role_arn` /
`source_profile` assumed roles, web-identity / IRSA, ECS, and EC2 instance
profiles — with no access keys in `settings.xml`. The region is resolved the
same way, so a single artifact works unchanged in commercial and GovCloud
regions.

> **2.0.0 is not a drop-in upgrade from 1.x.** The coordinates and the `s3://`
> URL syntax are unchanged, but 2.0.0 requires **Java 17+**, removes the
> `settings.xml` `<server>` access-key/secret path, and no longer sends an
> object ACL. See [Upgrading from 1.x](#upgrading-from-1x).

Requirements
------------

- **Java 17 or newer** (the wagon is compiled for a Java 17 baseline).
- A build using Maven 3.8+.
- An S3 bucket reachable with the credentials resolved from the default chain.

Usage
-----

Register the wagon as a build extension; the coordinates are unchanged from 1.x,
only the version differs:

```xml
<build>
    <extensions>
        <extension>
            <groupId>com.allogy.maven.wagon</groupId>
            <artifactId>maven-s3-wagon</artifactId>
            <version>2.0.0</version>
        </extension>
    </extensions>
</build>
```

Then point a repository (for resolving) and/or distributionManagement (for
deploying) at an `s3://` URL. The URL **host is the bucket** and the **path is a
key prefix**:

```xml
<distributionManagement>
    <repository>
        <id>my-s3-release</id>
        <url>s3://my-bucket/release</url>
    </repository>
    <snapshotRepository>
        <id>my-s3-snapshot</id>
        <url>s3://my-bucket/snapshot</url>
    </snapshotRepository>
</distributionManagement>

<repositories>
    <repository>
        <id>my-s3-release</id>
        <url>s3://my-bucket/release</url>
    </repository>
</repositories>
```

Credentials
-----------

Credentials come **only** from the AWS default credential chain. There is no
`settings.xml` username/password path. Configure whichever source fits your
environment; the wagon needs no extra configuration for any of them:

- **Environment variables** — `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
  optional `AWS_SESSION_TOKEN`.
- **Named profile** — `AWS_PROFILE=my-profile` (reads `~/.aws/credentials` /
  `~/.aws/config`).
- **Assumed role** — a profile with `role_arn` + `source_profile` (or
  `credential_source`); the chain performs the STS `AssumeRole` for you.
- **SSO** — an `sso_*` profile after `aws sso login`.
- **Web identity / IRSA** — `AWS_WEB_IDENTITY_TOKEN_FILE` + `AWS_ROLE_ARN`
  (EKS/CI OIDC).
- **ECS / EC2** — container credentials or the EC2 instance profile, used
  automatically when nothing earlier in the chain matches.

Example — deploy using a profile that assumes a role:

```bash
AWS_PROFILE=deployer-role AWS_REGION=us-east-1 mvn deploy
```

Region
------

The region is resolved from the default region chain and is **never hardcoded**
in the wagon:

- `AWS_REGION` environment variable (or the `aws.region` system property), or
- the `region` setting of the active profile, or
- the EC2/ECS instance metadata.

For **GovCloud**, set the GovCloud region and otherwise change nothing:

```bash
AWS_REGION=us-gov-west-1 AWS_PROFILE=gov-deployer mvn deploy
```

Object ACLs
-----------

`put` sends **no** ACL. Uploaded objects inherit the bucket's default ownership
and access settings. This is the correct behavior for modern buckets:

- buckets with **Bucket owner enforced** (ACLs disabled) — the AWS default for
  new buckets — reject requests that carry a canned ACL, so sending one would
  fail; and
- buckets with **Block Public Access** rely on bucket policy rather than
  per-object ACLs.

The 1.x behavior of forcing a `private` canned ACL on every object is
intentionally removed. If you require per-object ACLs, enforce them with a
bucket policy or an S3 Object Ownership setting instead.

Upgrading from 1.x
------------------

| Area | 1.x | 2.0.0 |
|------|-----|-------|
| AWS SDK | v1 (`com.amazonaws`, via the Kuali parent) | v2 (`software.amazon.awssdk`), reimplemented in-house |
| Java baseline | 8 | **17+** |
| Credentials | access key/secret in `settings.xml` `<server>` | **AWS default credential chain** (env / profile / SSO / assumed role / IRSA / instance) |
| Region | implicit | AWS default region chain (`AWS_REGION` / profile / IMDS) |
| Object ACL | forced `private` canned ACL | **no ACL** — inherits bucket defaults |
| Coordinates & `s3://` URL | — | unchanged |

Migration steps:

1. **Bump the extension version** to `2.0.0`. The `groupId`, `artifactId`, and
   `s3://` URL syntax are unchanged.
2. **Build on Java 17+.**
3. **Remove the S3 `<server>` access-key/secret entries** from `settings.xml`
   and configure the default credential chain instead (see
   [Credentials](#credentials)). An `AWS_PROFILE` or environment variables are
   usually all that is needed.
4. **Set the region** via `AWS_REGION` or your profile if it is not already
   configured.
5. **Confirm your bucket does not require a canned ACL.** On a Bucket-owner-
   enforced bucket (the modern default) no change is needed; if you relied on
   the old forced-`private` ACL, move that intent into a bucket policy or Object
   Ownership setting.

Building
--------

```bash
# unit tests (no Docker required)
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn clean test

# LocalStack integration tests + real-Maven producer/consumer fixture (needs Docker)
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -Pit verify

# build the shaded extension jar
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -DskipTests package
```

The published jar is a shaded ("uber") jar: the AWS SDK and its transitive
dependencies are relocated under `com.allogy.build.maven.shaded` so the wagon
cannot clash with other versions of those libraries on Maven's extension
classpath.
