package com.google.gson.internal.sql;

import com.google.gson.TypeAdapterFactory;
import com.google.gson.internal.bind.DefaultDateTypeAdapter;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Browser-safe SqlTypesSupport. java.sql.* is absent from TeaVM, so SQL date
 * adapters are simply not registered.
 */
public final class SqlTypesSupport {
    public static final boolean SUPPORTS_SQL_TYPES = false;
    public static final DefaultDateTypeAdapter.DateType<? extends Date> DATE_DATE_TYPE = null;
    public static final DefaultDateTypeAdapter.DateType<? extends Date> TIMESTAMP_DATE_TYPE = null;
    public static final TypeAdapterFactory DATE_FACTORY = null;
    public static final TypeAdapterFactory TIME_FACTORY = null;
    public static final TypeAdapterFactory TIMESTAMP_FACTORY = null;
    public static final List<TypeAdapterFactory> SQL_TYPE_FACTORIES = Collections.emptyList();

    private SqlTypesSupport() {
    }
}
