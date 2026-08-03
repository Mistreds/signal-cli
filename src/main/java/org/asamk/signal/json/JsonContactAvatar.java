package org.asamk.signal.json;

import org.asamk.signal.manager.api.MessageEnvelope;

import io.micronaut.jsonschema.JsonSchema;

@JsonSchema(title = "ContactAvatar")
public record JsonContactAvatar(JsonAttachment attachment, boolean isProfile) {

    static JsonContactAvatar from(MessageEnvelope.Data.SharedContact.Avatar avatar) {
        return new JsonContactAvatar(JsonAttachment.from(avatar.attachment()), avatar.isProfile());
    }
}
