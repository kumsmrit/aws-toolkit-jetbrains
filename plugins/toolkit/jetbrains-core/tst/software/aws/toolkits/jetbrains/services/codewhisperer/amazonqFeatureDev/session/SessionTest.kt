// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.amazonqFeatureDev.session

import com.intellij.testFramework.RuleChain
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import software.amazon.awssdk.services.codewhispererruntime.CodeWhispererRuntimeClient
import software.amazon.awssdk.services.codewhispererruntime.model.CreateTaskAssistConversationRequest
import software.amazon.awssdk.services.ssooidc.SsoOidcClient
import software.aws.toolkits.core.utils.test.aString
import software.aws.toolkits.jetbrains.core.MockClientManagerRule
import software.aws.toolkits.jetbrains.core.credentials.AwsBearerTokenConnection
import software.aws.toolkits.jetbrains.core.credentials.ManagedSsoProfile
import software.aws.toolkits.jetbrains.core.credentials.MockToolkitAuthManagerRule
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManager
import software.aws.toolkits.jetbrains.services.amazonq.messages.MessagePublisher
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.clients.FeatureDevClient
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.session.ConversationNotStartedState
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.session.PrepareRefinementState
import software.aws.toolkits.jetbrains.services.amazonqFeatureDev.session.Session
import software.aws.toolkits.jetbrains.services.codewhisperer.amazonqFeatureDev.FeatureDevTestBase

class SessionTest : FeatureDevTestBase() {
    val mockClientManagerRule = MockClientManagerRule()

    @Rule
    @JvmField
    val ruleChain = RuleChain(projectRule, mockClientManagerRule, disposableRule)

    private lateinit var ssoClient: SsoOidcClient
    private lateinit var bearerClient: CodeWhispererRuntimeClient
    private val authManagerRule = MockToolkitAuthManagerRule()

    private lateinit var featureDevClient: FeatureDevClient
    private lateinit var connectionManager: ToolkitConnectionManager
    private lateinit var session: Session
    private lateinit var messanger: MessagePublisher

    @Before
    override fun setup() {
        super.setup()
        featureDevClient = FeatureDevClient.getInstance(projectRule.project)
        ssoClient = mockClientManagerRule.create()
        session = Session(project = projectRule.project, tabID = "tabId")
        messanger = mock()

        bearerClient = mockClientManagerRule.create<CodeWhispererRuntimeClient>().stub {
            on { createTaskAssistConversation(any<CreateTaskAssistConversationRequest>()) } doReturn exampleCreateTaskAssistConversationResponse
        }

        connectionManager = mock {
            on {
                activeConnectionForFeature(any())
            } doReturn authManagerRule.createConnection(ManagedSsoProfile("us-east-1", aString(), emptyList())) as AwsBearerTokenConnection
        }
        projectRule.project.replaceService(ToolkitConnectionManager::class.java, connectionManager, disposableRule.disposable)
    }

    @Test
    fun `test session before preloader`() = runTest {
        assertThat(session.sessionState).isInstanceOf(ConversationNotStartedState::class.java)
        assertThat(session.isAuthenticating).isFalse()
    }

    @Test
    fun `test preloader`() = runTest {
        session.preloader(userMessage, messanger)
        assertThat(session.conversationId).isEqualTo(testConversationId)
        assertThat(session.sessionState).isInstanceOf(PrepareRefinementState::class.java)
    }
}
