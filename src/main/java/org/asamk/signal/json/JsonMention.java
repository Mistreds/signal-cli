package org.asamk.signal.json;

import org.asamk.signal.manager.api.MessageEnvelope;

import java.util.UUID;

import io.micronaut.jsonschema.JsonSchema;

@JsonSchema(title = "Mention")
public record JsonMention(@Deprecated String name, String number, String uuid, int start, int length) {

    static JsonMention from(MessageEnvelope.Data.Mention mention) {
        final var address = mention.recipient();
        return new JsonMention(address.getLegacyIdentifier(),
                address.number().orElse(null),
                address.uuid().map(UUID::toString).orElse(null),
                mention.start(),
                mention.length());
    }
}
