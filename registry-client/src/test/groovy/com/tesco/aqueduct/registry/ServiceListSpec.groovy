package com.tesco.aqueduct.registry

import io.micronaut.http.client.DefaultHttpClientConfiguration
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

@Newify(URL)
class ServiceListSpec extends Specification {

    final static URL URL_1 = URL("http://a1")
    final static URL URL_2 = URL("http://a2")
    final static URL URL_3 = URL("http://a3")
    final PipeServiceInstance serviceInstance = new PipeServiceInstance(config, URL_1)

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    def config
    def existingPropertiesFile

    def setup() {
        config = new DefaultHttpClientConfiguration()
        existingPropertiesFile = folder.newFile()
    }

    def "services that are updated are returned in the getServices"() {
        given: "a service list"
        ServiceList serviceList = new ServiceList(config, serviceInstance, existingPropertiesFile)
        def list = [URL_1, URL_2, URL_3]

        when: "service list is updated"
        serviceList.update(list)

        then: "list returned matches updated list"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == list
    }

    def "when services are updated, obsolete services are removed"() {
        given: "a service list"
        ServiceList serviceList = new ServiceList(config, serviceInstance, existingPropertiesFile)

        and: "the service list has been updated before"
        serviceList.update([URL_1, URL_2])

        when: "service list is updated with a new list"
        def list = [URL_2, URL_3]
        serviceList.update(list)

        then: "list returned matches updated list"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == list
    }

    def "when services are updated, previous services keep their status"() {
        given: "a service list"
        ServiceList serviceList = new ServiceList(config, serviceInstance, existingPropertiesFile)

        and: "the service list has been updated before"
        serviceList.update([URL_1, URL_2])

        and: "the service statuses have been set"
        serviceList.services[0].isUp(true)
        serviceList.services[1].isUp(false)

        when: "service list is updated with a new list"
        def list = [URL_1, URL_2, URL_3]
        serviceList.update(list)

        then: "list returned matches updated list"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == list

        and: "service statuses have not been altered"
        serviceList.stream().map({p -> p.isUp()}).collect() == [true, false, true]
    }

    def "service list always contains at least the cloud url"() {
        given: "a service list"
        ServiceList serviceList = new ServiceList(config, serviceInstance, existingPropertiesFile)

        when: "the service list has not been updated yet"

        then: "the service list has the cloud_url"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == [URL_1]

        when: "the service list is updated with a null list"
        serviceList.update(null)

        then: "the service list has the cloud url"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == [URL_1]

        when: "the service list is updated with an empty list"
        serviceList.update([])

        then: "the service list has the cloud url"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == [URL_1]
    }

    def "service list does not contain cloud url if updated with a list without it"() {
        given: "a service list"
        ServiceList serviceList = new ServiceList(config, serviceInstance, existingPropertiesFile)

        when: "the service list is updated with a list without the cloud url"
        serviceList.update([URL_2, URL_3])

        then: "the service list does not have the cloud url"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == [URL_2, URL_3]

        when: "the service list is updated with an empty list"
        serviceList.update([])

        then: "the service list has the cloud url"
        serviceList.stream().map({ p -> p.getUrl()}).collect() == [URL_1]
    }

    def "service list reads persisted list on startup"() {
        given: "a persisted list"
        def existingPropertiesFile = folder.newFile()
        existingPropertiesFile.write("""services=$URL_2,$URL_3""")

        when: "a new service list is created"
        def config = new DefaultHttpClientConfiguration()
        ServiceList serviceList = new ServiceList(config, new PipeServiceInstance(config, URL_1), existingPropertiesFile)

        then: "the services returned are the persisted list"
        serviceList.stream().map({m -> m.getUrl()}).collect() == [URL_2, URL_3]
    }

    def "service list is just cloud when properties file is empty"() {
        given: "an empty properties file"
        def existingPropertiesFile = folder.newFile()

        when: "a new service list is created"
        def config = new DefaultHttpClientConfiguration()
        ServiceList serviceList = new ServiceList(config, new PipeServiceInstance(config, URL_1), existingPropertiesFile)

        then: "the services returned is just the cloud URL"
        serviceList.stream().map({m -> m.getUrl()}).collect() == [URL_1]
    }

    def "service list is just cloud when properties file doesn't exist"() {
        given: "a deleted properties file"
        def existingPropertiesFile = folder.newFile()
        existingPropertiesFile.delete()

        when: "a new service list is created"
        def config = new DefaultHttpClientConfiguration()
        ServiceList serviceList = new ServiceList(config, new PipeServiceInstance(config, URL_1), existingPropertiesFile)

        then: "the services returned is just the cloud URL"
        serviceList.stream().map({m -> m.getUrl()}).collect() == [URL_1]
    }

    def "service list persists when list is updated"() {
        given: "a service list with a file"
        def existingPropertiesFile = folder.newFile()
        def config = new DefaultHttpClientConfiguration()
        ServiceList serviceList = new ServiceList(config, new PipeServiceInstance(config, URL_1), existingPropertiesFile)

        when: "service list is updated"
        serviceList.update([URL_2, URL_3])

        then: "the file contains the URL's"
        existingPropertiesFile.getText().contains("services=http\\://a2,http\\://a3")
    }

    def "services persisted by one service list can be read by another"() {
        given: "service list"
        def existingPropertiesFile = folder.newFile()
        def config = new DefaultHttpClientConfiguration()
        ServiceList serviceList = new ServiceList(config, new PipeServiceInstance(config, URL_1), existingPropertiesFile)

        when: "service list is updated and persists"
        serviceList.update([URL_2, URL_3])

        and: "a second service list is created"
        ServiceList serviceList2 = new ServiceList(config, new PipeServiceInstance(config, URL_1), existingPropertiesFile)

        then: "the second service list can read the persisted values"
        serviceList2.stream().map({m -> m.getUrl()}).collect() == [URL_2, URL_3]
    }
}
