package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class GeneratedStoreContractRuntimeTest {

    @Test
    void schemaSpecificFactorySupportsTypedAndRowOrientedOperations() {
        PriceProjectionStore prices = PriceProjectionStore.create(2);

        prices.batch()
                .price(new double[] {15.1D, 25.2D})
                .symbol(new String[] {"A", "B"})
                .append();
        prices.batch(1, 3)
                .price(new double[] {0.0D, 35.3D, 45.4D})
                .symbol(new String[] {"ignored", "C", "D", "unused"})
                .append();
        prices.add(price(55.5D, "E"));

        ProjectionStore<PriceProjection> commonStore = prices;
        assertSame(prices, commonStore);
        assertEquals(5, prices.size());

        prices.seal();
        assertPrice(prices.viewAt(0), 15.1D, "A");
        assertPrice(prices.viewAt(4), 55.5D, "E");

        ProjectionCursor<PriceProjection> cursor = prices.cursor();
        int rowCount = 0;
        while (cursor.moveNext()) {
            rowCount++;
        }
        assertEquals(5, rowCount);
    }

    @Test
    void commonFactoryAndDirectConstructionRemainCompatible()
            throws Exception {
        ProjectionStore<PriceProjection> common =
                ProjectionStores.create(PriceProjection.class, 1);
        assertInstanceOf(PriceProjectionStore.class, common);
        assertInstanceOf(
                PriceProjection__ColumnarProjectionStore.class, common);

        PriceProjection__ColumnarProjectionStore direct =
                new PriceProjection__ColumnarProjectionStore(1);
        PriceProjectionStore contract = direct;
        ProjectionStore<PriceProjection> generic = direct;
        assertSame(direct, contract);
        assertSame(direct, generic);

        Method commonFactory = ProjectionStores.class.getMethod(
                "create", Class.class, Integer.TYPE);
        assertSame(ProjectionStore.class, commonFactory.getReturnType());
        assertSame(PriceProjectionStore.class,
                PriceProjectionStore.class.getMethod(
                        "create", Integer.TYPE).getReturnType());
        assertSame(Void.TYPE, PriceProjectionStore.class.getMethod(
                "price", double[].class).getReturnType());
        assertSame(Void.TYPE, PriceProjectionStore.class.getMethod(
                "price", double[].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertSame(Void.TYPE, PriceProjectionStore.class.getMethod(
                "symbol", String[].class).getReturnType());
        assertSame(Void.TYPE, PriceProjectionStore.class.getMethod(
                "symbol", String[].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());

        Constructor<PriceProjection__ColumnarProjectionStore>
                compatibleConstructor =
                        PriceProjection__ColumnarProjectionStore.class
                                .getConstructor(Integer.TYPE);
        assertTrue(Modifier.isPublic(
                compatibleConstructor.getModifiers()));
        assertThrows(IllegalArgumentException.class,
                () -> PriceProjectionStore.create(-1));
    }

    @Test
    void concreteAndPrivateBatchTypesImplementGeneratedContracts()
            throws Exception {
        Class<?> concrete = PriceProjection__ColumnarProjectionStore.class;
        Class<?> storeContract = PriceProjectionStore.class;
        Class<?> batchContract = PriceProjectionStore.Batch.class;

        assertTrue(storeContract.isAssignableFrom(concrete));
        assertTrue(ProjectionStore.class.isAssignableFrom(concrete));
        assertSame(batchContract,
                concrete.getMethod("batch").getReturnType());
        assertSame(batchContract,
                concrete.getMethod(
                        "batch", Integer.TYPE, Integer.TYPE).getReturnType());

        Class<?> batchImplementation = null;
        Class<?> generationProvenance = null;
        for (Class<?> nested : concrete.getDeclaredClasses()) {
            if (batchContract.isAssignableFrom(nested)) {
                batchImplementation = nested;
            } else if (nested.isAnnotation()) {
                generationProvenance = nested;
            }
        }
        assertTrue(batchImplementation != null);
        assertTrue(generationProvenance != null);
        assertTrue(Modifier.isPrivate(batchImplementation.getModifiers()));
        assertFalse(Modifier.isPublic(generationProvenance.getModifiers()));
        assertFalse(Modifier.isProtected(
                generationProvenance.getModifiers()));
        assertEquals(1, batchImplementation.getInterfaces().length);
        assertSame(batchContract, batchImplementation.getInterfaces()[0]);
        for (Method method : batchImplementation.getDeclaredMethods()) {
            assertFalse(method.isBridge(), method.toString());
        }
    }

    private static PriceProjection price(
            final double price, final String symbol) {
        return new PriceProjection() {
            @Override
            public double price() {
                return price;
            }

            @Override
            public String symbol() {
                return symbol;
            }
        };
    }

    private static void assertPrice(
            PriceProjection row, double price, String symbol) {
        assertEquals(price, row.price());
        assertEquals(symbol, row.symbol());
    }
}

@ProjectionSchema
interface PriceProjection {
    double price();

    String symbol();
}
