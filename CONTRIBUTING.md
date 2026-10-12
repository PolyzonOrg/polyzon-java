# Contributing

Thanks for helping! Bug reports and pull requests are welcome at
https://github.com/PolyzonOrg/polyzon-java. Security issues go to security@polyzon.org
instead (see [SECURITY.md](SECURITY.md)).

## Development

You need JDK 17 or later and Maven 3.6.3 or later.

```sh
mvn verify
```

This compiles with `-Xlint:all -Werror`, runs the tests against a local mock server,
enforces at least 90 % line coverage (JaCoCo, report in `target/site/jacoco/`), and builds
the javadoc (doclint on, warnings fail the build) and sources jars. The tests never call the
real API.

Check the examples compile:

```sh
mvn -q compile && javac -cp target/classes -d target/examples examples/*.java
```

## Pull requests

* One topic per pull request, with a test for every behaviour change.
* Keep the zero runtime dependency rule; the build fails on any non-test dependency.
* Document every public symbol (javadoc) and update `README.md`, `README.ru.md` and
  `CHANGELOG.md` (under *Unreleased*) when behaviour changes.
* Use [Conventional Commits](https://www.conventionalcommits.org/) for titles, for example
  `fix: honour Retry-After dates` or `feat: add formats filter`.
