package org.dedira.qrnotas.util;

public interface IDatabaseOnLoad<T> {
    void onLoadComplete(boolean success, T object);
}
