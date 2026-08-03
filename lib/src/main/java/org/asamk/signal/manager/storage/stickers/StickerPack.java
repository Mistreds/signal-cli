package org.asamk.signal.manager.storage.stickers;

import org.asamk.signal.manager.api.StickerPackId;
import org.whispersystems.signalservice.api.storage.StorageId;

public record StickerPack(
        long internalId,
        StickerPackId packId,
        byte[] packKey,
        boolean isInstalled,
        int position,
        long deletedTimestamp,
        StorageId storageId,
        byte[] storageRecord
) {

    public StickerPack(
            final long internalId,
            final StickerPackId packId,
            final byte[] packKey,
            final boolean isInstalled
    ) {
        this(internalId, packId, packKey, isInstalled, 0, 0, null, null);
    }

    public StickerPack(final StickerPackId packId, final byte[] packKey) {
        this(-1, packId, packKey, false, 0, 0, null, null);
    }
}
