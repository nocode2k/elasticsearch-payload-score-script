package nocode.elasticsearch.plugin;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class CustomPayloadScoreScriptPlugin extends Plugin implements ScriptPlugin {

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new NoCodeScriptEngine();
    }


    private static class NoCodeScriptEngine implements ScriptEngine {
        private final String _SOURCE_VALUE = "payload_script";
        private final String _LANG_VALUE = "nocode";

        @Override
        public String getType() {
            return _LANG_VALUE;
        }

        @Override
        public <T> T compile(String scriptName, String scriptSource, ScriptContext<T> context, Map<String, String> params) {

            if (!context.equals(ScoreScript.CONTEXT)) {
                throw new IllegalArgumentException(getType()
                        + " scripts cannot be used for context ["
                        + context.name + "]");
            }

            // we use the script "source" as the script identifier
            if (_SOURCE_VALUE.equals(scriptSource)) {
                ScoreScript.Factory factory = CustomPayloadScoreFactory::new;
                return context.factoryClazz.cast(factory);
            }
            throw new IllegalArgumentException("Unknown script name " + scriptSource);
        }

        @Override
        public void close() {
            // optionally close resources
        }

        @Override
        public Set<ScriptContext<?>> getSupportedContexts() {
            return Set.of(ScoreScript.CONTEXT);
        }
    }
}