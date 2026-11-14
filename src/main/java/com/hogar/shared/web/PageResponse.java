package com.hogar.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Página estable para la API: no se serializa {@code Page} de Spring Data directamente porque su
 * JSON no es un contrato estable (Spring lo desaconseja desde Boot 3.3).
 */
@Schema(description = "Página de resultados.")
public record PageResponse<T>(
        @Schema(description = "Elementos de la página.")
        List<T> content,
        @Schema(description = "Número de página (desde 0).", example = "0")
        int page,
        @Schema(description = "Tamaño de página pedido (1–100).", example = "20")
        int size,
        @Schema(description = "Total de elementos que cumplen los filtros.", example = "42")
        long totalElements,
        @Schema(description = "Total de páginas.", example = "3")
        int totalPages) {

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
