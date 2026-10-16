package org.abstruck.brush.launch.meta;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * arguments.game / arguments.jvm 的条目有两种形态：
 * <pre>
 *   "xxx"                          无条件字符串
 *   {"rules":[...], "value": ...}  带条件的字符串或字符串数组
 * </pre>
 * 统一归一化为 {@link VersionInfo.Argument#values} 字符串列表。
 */
public final class ArgumentDeserializer implements JsonDeserializer<VersionInfo.Argument> {

    private static final Type RULE_LIST = new TypeToken<List<VersionInfo.Rule>>() { }.getType();

    @Override
    public VersionInfo.Argument deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        VersionInfo.Argument argument = new VersionInfo.Argument();
        argument.values = new ArrayList<>();
        if (json.isJsonPrimitive()) {
            argument.values.add(json.getAsString());
            return argument;
        }
        if (json.isJsonObject()) {
            JsonObject object = json.getAsJsonObject();
            JsonElement rules = object.get("rules");
            if (rules != null && rules.isJsonArray()) {
                argument.rules = context.deserialize(rules, RULE_LIST);
            }
            JsonElement value = object.get("value");
            if (value != null) {
                if (value.isJsonArray()) {
                    JsonArray array = value.getAsJsonArray();
                    for (JsonElement element : array) {
                        argument.values.add(element.getAsString());
                    }
                } else {
                    argument.values.add(value.getAsString());
                }
            }
            return argument;
        }
        throw new JsonParseException("unsupported argument entry: " + json);
    }
}
