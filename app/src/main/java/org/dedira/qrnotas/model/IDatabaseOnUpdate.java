package org.dedira.qrnotas.model;

public interface IDatabaseOnUpdate<T> {
    void onUpdateComplete(boolean success, T object);
}
