package com.jcoding.workflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in live provider and sandbox probe; no repository or GitHub mutations. */
final class ProviderCanary {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProviderCanary.class);
    private ProviderCanary() { }

    static boolean run(Map<String, Object> config) {
        Path prompt = null;
        try {
            prompt = Files.createTempFile("jcoding-provider-canary-", ".txt");
            Files.writeString(prompt, "Reply with exactly JCODING_CANARY_OK. Do not use tools or edit files.\n");
            Map<String, Object> scoped = new LinkedHashMap<>(config);
            Map<String, Object> codex = new LinkedHashMap<>(Config.section(config, "codex"));
            codex.put("diagnostic_read_only", true);
            codex.put("execution_timeout_seconds", 90);
            scoped.put("codex", codex);
            Path repo = Path.of(Engine.asString(Config.section(config, "repository").get("local_path")));
            if (!CodexLogin.enabled(codex) && !probeStreamingGateway(repo, codex)) {
                LOG.warn("PROVIDER CANARY: scoped gateway streaming or credential-isolation stage failed");
                return false;
            }
            CodexRunner.CodexResult result = new CodexRunner().run(scoped, prompt, repo, false, null, 90);
            boolean passed = result.exitCode() == 0 && "JCODING_CANARY_OK".equals(result.output().strip());
            if (!passed) LOG.warn("PROVIDER CANARY: Codex provider stage failed with exit {}", result.exitCode());
            return passed;
        } catch (java.io.IOException | RuntimeException exc) {
            LOG.warn("PROVIDER CANARY: startup failed: {}", exc.getClass().getSimpleName());
            return false;
        }
        finally {
            if (prompt != null) {
                try { Files.deleteIfExists(prompt); } catch (java.io.IOException ignored) { }
            }
        }
    }

    private static boolean probeStreamingGateway(Path repo, Map<String, Object> codex) throws java.io.IOException {
        String script = """
                import http.client,json,os
                assert os.getenv('JCODING_PROVIDER_SECRET') is None
                assert os.getenv('GH_TOKEN') is None
                assert os.getenv(os.environ['JCODING_CANARY_SECRET_NAME']) is None
                c=http.client.HTTPConnection('127.0.0.1',18443,timeout=60)
                body=json.dumps({'model':os.environ['JCODING_CANARY_MODEL'],
                  'input':'Reply with OK.','stream':True,'store':False,'max_output_tokens':64})
                c.request('POST','/v1/responses',body,
                  {'Authorization':'Bearer '+os.environ['JCODING_RUN_TOKEN'],
                   'Content-Type':'application/json'})
                r=c.getresponse()
                assert r.status==200, 'provider returned non-success'
                data=bytearray()
                while True:
                    part=r.read(4096)
                    if not part: break
                    data.extend(part)
                    assert len(data)<1048576, 'stream exceeded canary limit'
                assert b'response.completed' in data, 'completion event missing'
                c.close()
                """;
        try (WorkerIsolation isolation = WorkerIsolation.create(true)) {
            isolation.startGateway(codex, 75);
            List<String> wrapped = isolation.wrap(List.of("/usr/bin/python3", "-c", script), repo, codex, true, true);
            Map<String, String> environment = isolation.modelEnvironment();
            environment.put("JCODING_CANARY_MODEL", Engine.asString(codex.get("model")));
            environment.put("JCODING_CANARY_SECRET_NAME", Engine.asString(Config.section(codex, "gateway")
                    .getOrDefault("credential_environment_variable", "OPENAI_API_KEY")));
            BoundedProcess.Result result = BoundedProcess.run(wrapped, repo, environment, 75, null);
            if (result.exitCode() != 0) LOG.warn("PROVIDER CANARY: scoped gateway probe exited {}", result.exitCode());
            return result.exitCode() == 0 && !result.truncated();
        }
    }
}
