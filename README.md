# Camunda Process Test in a deployment pipeline: JSON test cases

An example of how to use [Camunda Process Test (CPT)](https://docs.camunda.io/docs/next/apis-tools/testing/getting-started/)
as a quality gate in a deployment pipeline:

- **Test cases are JSON files**, readable and writable by modelers and business analysts.
- **Java is a one-time setup**: one `pom.xml` and one ~15-line test class. Adding a
  scenario never touches Java again.
- **The pipeline blocks broken models**: a change to a BPMN or DMN that breaks an existing
  test case fails the CI check, and the pull request can't be merged.

```
Modeler edits BPMN/DMN  ->  Git push / PR  ->  CI runs mvn test  ->  red check blocks merge
(e.g. Web Modeler Git Sync)                    (CPT + Testcontainers)   green check allows deploy
```

## Requirements

- **Camunda 8.9 or later.** The JSON test-case DSL (`camunda-process-test-json-test-cases`)
  is not available in earlier versions.
- Docker (CPT starts a Camunda runtime via Testcontainers)
- JDK 21 and Maven

## Quick start

```bash
mvn test
```

CPT starts a Camunda runtime in a container, deploys the BPMN/DMN from `src/main/resources`,
runs every JSON test case against it, and shuts it down. No shared cluster is involved, so
tests can't leave state behind or interfere with other teams.

Afterwards, open the coverage report, which shows the BPMN diagram with covered paths
highlighted:

```
target/coverage-report/report.html
```

## Repository layout

```
├── pom.xml                                   <- technical, set up once
├── src/
│   ├── main/resources/                       <- your models (e.g. Web Modeler Git Sync target)
│   │   ├── invoice-approval.bpmn
│   │   └── credit-check.dmn
│   └── test/
│       ├── java/.../ProcessTests.java        <- technical, set up once
│       └── resources/
│           ├── camunda-container-runtime.properties
│           └── test-cases/                   <- business content: one file per scenario
│               ├── happy-path.json
│               ├── rejection.json
│               └── archive-error.json
└── README.md
```

Only `pom.xml` and `ProcessTests.java` are technical. Everything else is a model or a
test case.

## The minimal Java setup

[ProcessTests.java](src/test/java/com/camunda/cpt/test/ProcessTests.java) deploys the
models and hands every JSON file in `test-cases/` to CPT's test-case runner:

```java
@CamundaProcessTest
@TestDeployment(resources = {"invoice-approval.bpmn", "credit-check.dmn"})
class ProcessTests {

  private TestCaseRunner testCaseRunner;

  @ParameterizedTest(name = "{0}")
  @TestCaseSource
  void runTestCase(TestCase testCase) {
    testCaseRunner.run(testCase);
  }
}
```

The [pom.xml](pom.xml) needs two test-scoped dependencies besides JUnit:
`camunda-process-test-java` and `camunda-process-test-json-test-cases`.

If you add new BPMN/DMN files, list them in `@TestDeployment`. That is the only reason to
touch the Java file again.

## The example process

`invoice-approval.bpmn` runs an invoice through the `credit-check.dmn` decision
(credit score ≥ 600 → approved). Approved invoices are archived by a job worker
(`archive-invoice`); rejected ones end immediately. A boundary error event on the archive
task routes the BPMN error `ARCHIVE_ERROR` to its own end event.

## Writing test cases

A test case is a list of instructions: mock workers, start an instance, assert the
outcome. For example [archive-error.json](src/test/resources/test-cases/archive-error.json):

```json
{
  "testCases": [
    {
      "name": "archive-error",
      "description": "Invoice is approved but the archiving step fails with a BPMN error",
      "instructions": [
        {
          "type": "MOCK_JOB_WORKER_THROW_BPMN_ERROR",
          "jobType": "archive-invoice",
          "errorCode": "ARCHIVE_ERROR"
        },
        {
          "type": "CREATE_PROCESS_INSTANCE",
          "processDefinitionSelector": {
            "processDefinitionId": "invoice-approval"
          },
          "variables": {
            "invoiceId": "INV-1003",
            "amount": 3000,
            "creditScore": 800
          }
        },
        {
          "type": "ASSERT_PROCESS_INSTANCE",
          "processInstanceSelector": {
            "processDefinitionId": "invoice-approval"
          },
          "state": "IS_COMPLETED"
        },
        {
          "type": "ASSERT_ELEMENT_INSTANCES",
          "processInstanceSelector": {
            "processDefinitionId": "invoice-approval"
          },
          "elementSelectors": [{ "elementId": "EndEvent_ArchiveFailed" }],
          "state": "IS_COMPLETED_IN_ORDER"
        }
      ]
    }
  ]
}
```

To add a scenario, copy an existing file into `src/test/resources/test-cases/`, adjust
variables and assertions, and run `mvn test`. All external systems are mocked in the
test case itself (job workers, connectors), so no Java is needed for that either.

See the [CPT documentation](https://docs.camunda.io/docs/next/apis-tools/testing/getting-started/)
for the full list of instruction types.

> **Tip:** the JSON format works well with AI assistants. Give one the BPMN and an
> existing test case and ask it to draft the missing scenarios, then review them.

## Using it in a pipeline

The workflow in [.github/workflows/process-tests.yml](.github/workflows/process-tests.yml)
runs the tests on every push and pull request to the `test` branch (and on manual
dispatch). The core of it is three steps:

```yaml
- uses: actions/setup-java@v4
  with:
    java-version: "21"
    distribution: "temurin"
    cache: "maven"

- name: Run process tests
  run: mvn -B test

- name: Publish test report # per-test-case results shown on the PR
  if: always()
  uses: dorny/test-reporter@v1
  with:
    name: Process Test Results
    path: target/surefire-reports/*.xml
    reporter: java-junit
```

Each JSON test case shows up as its own named test in the check, and the raw surefire
reports are uploaded as a build artifact.

To make it a real gate, adjust the `branches:` filter to your integration branch and, in
GitHub, go to **Settings → Branches → branch protection → Require status checks →
`process-tests`**.

The same `mvn test` step works in any CI system that has Docker available. Add your
deployment step (e.g. `c8ctl deploy`) after the test step so only models that pass get
deployed.

### What a blocked change looks like

Change `credit-check.dmn` so the threshold moves from 600 to 750 without updating the
test cases. The `happy-path` scenario now fails, the check turns red, and the PR shows
"Merging is blocked". Either update the test case to reflect the new business rule or
revert the change. Both are deliberate decisions, which is the point of the gate.

## Configuration notes

[camunda-container-runtime.properties](src/test/resources/camunda-container-runtime.properties)
sets `runtimeMode=shared`, so one container is reused across test classes instead of
restarting per class. Budget a minute or two in CI for the first image pull.

The runtime uses the Camunda version of the `camunda-process-test-java` dependency by
default; override the image in that file if you need a specific one.

## Scope

This example intentionally uses a Testcontainers-managed runtime rather than a remote
cluster (CPT resets state between runs, which suits a dedicated test runtime, not a shared
environment), and mocks everything the process talks to rather than calling real systems.
