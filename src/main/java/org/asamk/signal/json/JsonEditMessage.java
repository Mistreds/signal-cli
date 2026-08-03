package org.asamk.signal.json;

import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.MessageEnvelope;

import io.micronaut.jsonschema.JsonSchema;

@JsonSchema(title = "EditMessage")
record JsonEditMessage(long targetSentTimestamp, JsonDataMessage dataMessage) {

    static JsonEditMessage from(MessageEnvelope.Edit editMessage, Manager m) {
        return new JsonEditMessage(editMessage.targetSentTimestamp(),
                JsonDataMessage.from(editMessage.dataMessage(), m));
    }
}
