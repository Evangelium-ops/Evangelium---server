package net.minecraft.world.level.levelgen;

import net.minecraft.util.KeyDispatchDataCodec;

/**
 * Knowledge - base class of the density functions {@link DensityFunctionCompiler} generates. A generated subclass computes one
 * NoiseChunk-wrapped density function tree as straight code; everything that is not about computing values (mapAll, bounds, codec)
 * goes to the interpreted tree it was compiled from, so the rest of the game sees the same function.
 */
public abstract class CompiledDensityFunction implements DensityFunction {
    protected final DensityFunction source;

    protected CompiledDensityFunction(DensityFunction source) {
        this.source = source;
    }

    public DensityFunction source() {
        return this.source;
    }

    /** compute() of the generated node with this id; only ids that {@link #fillDirect} is called with are handled */
    protected abstract double computeNode(int id, DensityFunction.FunctionContext context);

    /**
     * Exactly what provider.fillAllDirectly(values, node id) does for the two context providers that exist (NoiseChunk and its
     * slice filler), field writes in the same order, so caches further down see the same state; any other provider gets the call.
     */
    protected final void fillDirect(double[] values, DensityFunction.ContextProvider provider, int id) {
        if (provider.getClass() == NoiseChunk.class) {
            NoiseChunk chunk = (NoiseChunk) provider;
            chunk.arrayIndex = 0;
            for (int y = chunk.cellHeight - 1; y >= 0; y--) {
                chunk.inCellY = y;
                for (int x = 0; x < chunk.cellWidth; x++) {
                    chunk.inCellX = x;
                    for (int z = 0; z < chunk.cellWidth; z++) {
                        chunk.inCellZ = z;
                        values[chunk.arrayIndex++] = this.computeNode(id, chunk); // index taken and bumped before compute, as in NoiseChunk
                    }
                }
            }
        } else if (provider instanceof NoiseChunk.SliceFillingContextProvider slice) {
            NoiseChunk chunk = slice.noiseChunk();
            for (int i = 0; i < chunk.cellCountY + 1; i++) {
                chunk.cellStartBlockY = (i + chunk.cellNoiseMinY) * chunk.cellHeight;
                chunk.interpolationCounter++;
                chunk.inCellY = 0;
                chunk.arrayIndex = i;
                values[i] = this.computeNode(id, chunk);
            }
        } else {
            provider.fillAllDirectly(values, new Node(this, id));
        }
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return this.source.mapAll(visitor);
    }

    @Override
    public double minValue() {
        return this.source.minValue();
    }

    @Override
    public double maxValue() {
        return this.source.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return this.source.codec();
    }

    @Override
    public String toString() {
        return "Compiled[" + this.source + "]";
    }

    /** one generated node as a density function, only for context providers this class does not know */
    private record Node(CompiledDensityFunction owner, int id) implements DensityFunction {
        @Override
        public double compute(DensityFunction.FunctionContext context) {
            return this.owner.computeNode(this.id, context);
        }

        @Override
        public void fillArray(double[] array, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return this.owner.codec();
        }
    }
}
