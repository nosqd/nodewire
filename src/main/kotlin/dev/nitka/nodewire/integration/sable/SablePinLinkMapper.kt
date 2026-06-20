package dev.nitka.nodewire.integration.sable

import com.mojang.logging.LogUtils
import dev.nitka.nodewire.endpoint.EndpointRef
import dev.nitka.nodewire.link.PinLink
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper
import dev.rew1nd.sableschematicapi.compat.BlueprintRefTags
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag

/**
 * Makes a sink block's pin links survive a Sable schematic copy-paste.
 *
 * A pin link's source is a [SableSubLevelPayload]`(subLevelId, blockPos)` where
 * `blockPos` is the ABSOLUTE plot coordinate the source block lives at in the
 * parent level (signal routing does `level.getBlockEntity(blockPos)` directly).
 * A pasted copy lands in a NEW sub-level at a DIFFERENT plot region, so BOTH the
 * sub-level UUID AND the plot position must be remapped — otherwise the copy's
 * links keep pointing at the ORIGINAL structure's blocks.
 *
 * The position remap needs the blueprint-local frame, which only the SAVE
 * session exposes ([BlueprintSaveSession.blockRef]). So:
 *  * [save] — capture each link's source as a [BlueprintBlockRef] (blueprint-id
 *    + local pos) plus its original sub-level UUID, stash under [LINKS_KEY], and
 *    drop the plain `pin_links` from the blueprint. Links whose source isn't in
 *    the copied region get no ref → dropped.
 *  * [beforeLoadBlockEntity] — resolve each ref to its NEW plot pos
 *    ([BlueprintPlaceSession.mapBlock]) + new sub-level UUID
 *    ([BlueprintPlaceSession.mapSubLevel]), rebuild `pin_links` so the BE loads
 *    correct links.
 */
object SablePinLinkMapper : SableBlueprintBlockMapper {

    private val LOG = LogUtils.getLogger()
    private const val PIN_LINKS_KEY = "pin_links"
    private const val LINKS_KEY = "nw_schem_links"

    /** SAVE: turn each Sable-sourced pin link into a position-independent record. */
    override fun save(context: BlueprintBlockSaveContext, defaultTag: CompoundTag?): CompoundTag? {
        val tag = defaultTag ?: return defaultTag
        if (!tag.contains(PIN_LINKS_KEY)) return tag
        val links = decodeLinks(tag.get(PIN_LINKS_KEY)) ?: return tag

        val session = context.session()
        val records = ListTag()
        for (link in links) {
            val payload = link.source.payload as? SableSubLevelPayload ?: continue // non-sub-level → drop
            val ref = session.blockRef(payload.blockPos).orElse(null) ?: continue   // source outside copy → drop
            val rec = CompoundTag()
            PinLink.CODEC.encodeStart(NbtOps.INSTANCE, link).result().ifPresent { rec.put("link", it) }
            rec.putUUID("src_sub", payload.subLevelId)
            rec.put("ref", BlueprintRefTags.write(ref))
            records.add(rec)
        }
        tag.remove(PIN_LINKS_KEY)
        if (!records.isEmpty()) tag.put(LINKS_KEY, records)
        return tag
    }

    /** PLACE: rebuild `pin_links` with remapped sub-level UUID + plot position. */
    override fun beforeLoadBlockEntity(context: BlueprintBlockPlaceContext, tag: CompoundTag) {
        val records = tag.getList(LINKS_KEY, Tag.TAG_COMPOUND.toInt())
        if (records.isEmpty()) return
        val session = context.session()
        val out = ListTag()
        for (t in records) {
            val rec = t as? CompoundTag ?: continue
            val link = decodeLink(rec.get("link")) ?: continue
            val origSub = rec.getUUID("src_sub")
            val ref = BlueprintRefTags.read(rec, "ref").orElse(null) ?: continue
            val newPos = session.mapBlock(ref)
            val newSub = session.mapSubLevel(origSub)
            if (newPos == null || newSub == null) {
                LOG.info("[NW-SCHEM] drop link: unmapped (pos={}, sub={})", newPos, newSub)
                continue
            }
            val remapped = link.copy(source = EndpointRef(link.source.backendId, SableSubLevelPayload(newSub, newPos)))
            PinLink.CODEC.encodeStart(NbtOps.INSTANCE, remapped).result().ifPresent { out.add(it) }
            LOG.info("[NW-SCHEM] remap link -> sub {} pos {}", newSub, newPos.toShortString())
        }
        tag.remove(LINKS_KEY)
        if (!out.isEmpty()) tag.put(PIN_LINKS_KEY, out)
    }

    private fun decodeLinks(t: Tag?): List<PinLink>? =
        PinLink.CODEC.listOf().parse(NbtOps.INSTANCE, t).result().orElse(null)

    private fun decodeLink(t: Tag?): PinLink? =
        PinLink.CODEC.parse(NbtOps.INSTANCE, t).result().orElse(null)
}
