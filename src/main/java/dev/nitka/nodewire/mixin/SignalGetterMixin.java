package dev.nitka.nodewire.mixin;

import dev.nitka.nodewire.signal.VirtualSignalMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds concrete overrides of two {@code SignalGetter} default methods onto
 * {@link Level}. Vanilla puts the methods on the interface as defaults, so
 * Mixin's @Inject can't be used (interface injectors are unsupported by
 * Spongepowered Mixin). Instead, we add new methods directly to Level —
 * since interface defaults are only invoked when no implementor overrides,
 * adding our methods makes Level's overrides win every dispatch.
 *
 * Both methods preserve vanilla behaviour by re-implementing the default
 * logic (six-face scan + max), then layer our virtual map on top. We could
 * call {@code SignalGetter.super.getBestNeighborSignal(pos)} but the bytecode
 * verifier in dev environments is fussy about cross-package `super` so
 * inlining the same logic is more robust.
 */
@Mixin(Level.class)
public abstract class SignalGetterMixin {

    /**
     * Re-implementation of {@link net.minecraft.world.level.SignalGetter#getBestNeighborSignal}
     * augmented with our virtual signal map. By declaring it on Level we shadow
     * the interface default, so every callsite that goes through a Level
     * instance picks this up.
     */
    public int getBestNeighborSignal(BlockPos pos) {
        Level self = (Level) (Object) this;
        int best = 0;
        for (Direction d : Direction.values()) {
            int s = self.getSignal(pos.relative(d), d);
            if (s > best) best = s;
            if (best >= 15) return 15;
        }
        if (!self.isClientSide) {
            int virtual = VirtualSignalMap.INSTANCE.of(self).strongestAt(pos);
            if (virtual > best) best = virtual;
        }
        return best;
    }

    public boolean hasNeighborSignal(BlockPos pos) {
        Level self = (Level) (Object) this;
        for (Direction d : Direction.values()) {
            if (self.getSignal(pos.relative(d), d) > 0) return true;
        }
        if (!self.isClientSide
            && VirtualSignalMap.INSTANCE.of(self).strongestAt(pos) > 0) {
            return true;
        }
        return false;
    }
}
