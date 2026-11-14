package com.hogar.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Página estable para la API: no se serializa {@code Page} de Spring Data directamente porque su
 * JSON no es un contrato estable (Spring lo desaconseja desde Boot 3.3).
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /** Tamaño máximo de página de la API. */
    public static final int MAX_SIZE = 100;
    /**
     * Mayor {@code page} admitida: con más, {@code page * size} desborda un {@code int} y Spring Data
     * falla con un 500 ("Page offset exceeds Integer.MAX_VALUE"). Fuera de rango → 400 (D-043).
     */
    public static final int MAX_PAGE = Integer.MAX_VALUE / MAX_SIZE;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
