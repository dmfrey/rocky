package com.example.sharepointbatch.sharepoint.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.pivotal.cfenv.core.CfEnv;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

class FilesystemVolumeEnvironmentPostProcessorTest {

    private static final String VCAP_SERVICES = """
            {"block-storage":[{"name":"rag-docs-volume","label":"block-storage","tags":["block-storage"],
              "credentials":{},
              "volume_mounts":[{"container_dir":"/var/vcap/data/abc-123","device_type":"dedicated","mode":"rw"}]}],
             "postgres":[{"name":"rocky-db","label":"postgres","credentials":{},"volume_mounts":[]}]}""";

    private static FilesystemVolumeEnvironmentPostProcessor processor(String vcapServices) {
        return new FilesystemVolumeEnvironmentPostProcessor(() -> new CfEnv("{}", vcapServices));
    }

    private static StandardEnvironment environment(String... pairs) {
        StandardEnvironment environment = new StandardEnvironment();
        MockPropertySource source = new MockPropertySource("test");
        for (int i = 0; i < pairs.length; i += 2) {
            source.setProperty(pairs[i], pairs[i + 1]);
        }
        environment.getPropertySources().addFirst(source);
        return environment;
    }

    @Test
    void readsTheMountPathFromTheBoundVolumeService() {
        StandardEnvironment environment = environment();
        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("app.filesystem-source.path")).isEqualTo("/var/vcap/data/abc-123");
    }

    @Test
    void anExplicitPathAlwaysWins() {
        StandardEnvironment environment = environment("app.filesystem-source.path", "/mnt/elsewhere");
        processor(VCAP_SERVICES).postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("app.filesystem-source.path")).isEqualTo("/mnt/elsewhere");
    }

    @Test
    void usesTheVolumeServiceNamedInConfiguration() {
        String twoVolumes = """
                {"block-storage":[
                  {"name":"rag-docs-volume","credentials":{},"volume_mounts":[{"container_dir":"/var/vcap/data/one"}]},
                  {"name":"other-volume","credentials":{},"volume_mounts":[{"container_dir":"/var/vcap/data/two"}]}]}""";
        StandardEnvironment environment = environment("app.filesystem-source.volume-service", "other-volume");
        processor(twoVolumes).postProcessEnvironment(environment, null);
        assertThat(environment.getProperty("app.filesystem-source.path")).isEqualTo("/var/vcap/data/two");
    }

    @Test
    void leavesThePathUnsetWhenTheVolumeIsNotBound() {
        StandardEnvironment environment = environment();
        processor("{}").postProcessEnvironment(environment, null);
        assertThat(environment.containsProperty("app.filesystem-source.path")).isFalse();
    }

    @Test
    void leavesThePathUnsetWhenTheBindingHasNoMounts() {
        String noMounts = """
                {"block-storage":[{"name":"rag-docs-volume","credentials":{},"volume_mounts":[]}]}""";
        StandardEnvironment environment = environment();
        processor(noMounts).postProcessEnvironment(environment, null);
        assertThat(environment.containsProperty("app.filesystem-source.path")).isFalse();
    }

    @Test
    void fallsBackToTheDefaultPathWhenNothingSetsIt() {
        assertThat(new FilesystemSourceProperties(null).path()).isEqualTo("/mnt/rag-docs");
        assertThat(new FilesystemSourceProperties(" ").path()).isEqualTo("/mnt/rag-docs");
        assertThat(new FilesystemSourceProperties("/data").path()).isEqualTo("/data");
    }
}
