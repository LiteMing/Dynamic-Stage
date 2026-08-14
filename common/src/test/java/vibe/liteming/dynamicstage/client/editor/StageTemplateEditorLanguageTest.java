package vibe.liteming.dynamicstage.client.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StageTemplateEditorLanguageTest {
    @Test
    void editorLanguagesContainTheSameNonEmptyKeys() throws Exception {
        JsonObject english = language("en_us");
        JsonObject chinese = language("zh_cn");

        assertEquals(english.keySet(), chinese.keySet());
        english.entrySet().forEach(entry -> assertFalse(entry.getValue().getAsString().isBlank(), entry.getKey()));
        chinese.entrySet().forEach(entry -> assertFalse(entry.getValue().getAsString().isBlank(), entry.getKey()));
    }

    private static JsonObject language(String locale) throws Exception {
        String path = "assets/dynamicstage/lang/" + locale + ".json";
        InputStream input = StageTemplateEditorLanguageTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(input, path);
        try (input; InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
