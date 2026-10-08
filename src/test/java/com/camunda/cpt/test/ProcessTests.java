package com.camunda.cpt.test;

import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.TestDeployment;
import io.camunda.process.test.api.testCases.TestCase;
import io.camunda.process.test.api.testCases.TestCaseRunner;
import io.camunda.process.test.api.testCases.TestCaseSource;
import org.junit.jupiter.params.ParameterizedTest;

// One-time setup only: deploy the models and let CPT's JSON test-case DSL drive everything
// else. Add a new scenario by dropping a JSON file into src/test/resources/test-cases/ —
// no Java change needed here, including for new job-worker mocks.
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
