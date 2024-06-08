package org.dedira.qrnotas.model;

public interface IDatabaseOnDelete<T> {
    void onLoadComplete(boolean success, T object);
}
