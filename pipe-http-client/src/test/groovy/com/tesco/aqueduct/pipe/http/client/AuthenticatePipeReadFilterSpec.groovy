package com.tesco.aqueduct.pipe.http.client

import com.stehno.ersatz.ErsatzServer
import com.tesco.aqueduct.registry.PipeServiceInstance
import com.tesco.aqueduct.registry.ServiceList
import io.micronaut.context.ApplicationContext
import io.micronaut.http.client.DefaultHttpClientConfiguration
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class AuthenticatePipeReadFilterSpec extends Specification {

    @Shared @AutoCleanup ErsatzServer server
    @Shared @AutoCleanup("stop") ApplicationContext context

    InternalHttpPipeClient client

    def "basic auth is used when settings are provided"() {
        given: "latest offset requiring authentication"
        server = new ErsatzServer({
            authentication {
                basic 'admin', 'my-password'
            }
            expectations {
                get("/pipe/offset/latest") {
                    called(1)

                    responder {
                        contentType('application/json')
                        body('123')
                    }
                }
            }
        })
        server.start()

        and: "a authorized client"
        def config = new DefaultHttpClientConfiguration()
        context = ApplicationContext
            .build()
            .properties(
                "authentication.read-pipe.username": "admin",
                "authentication.read-pipe.password": "my-password",
                "pipe.http.latest-offset.attempts": 1,
                "pipe.http.latest-offset.delay": "1s",
                "pipe.http.client.url": server.getHttpUrl()
            )
            .build()
            .registerSingleton(new ServiceList(
                new DefaultHttpClientConfiguration(),
                new PipeServiceInstance(config, new URL(server.getHttpUrl())),
                File.createTempFile("provider", "properties")
        ))
            .start()

        client = context.getBean(InternalHttpPipeClient)

        when:
        def latest = client.getLatestOffsetMatching([])

        then:
        server.verify()
        latest == 123
    }
}
