package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.CategoryView;
import java.util.List;

/** Query actuator that lists all registered categories. */
public class ListCategoriesQueryActuator
        implements QueryActuator<
                ListCategoriesQueryActuator.ListCategoriesQuery, List<CategoryView>> {

    private final CategoryRepository categoryRepository;

    public ListCategoriesQueryActuator(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** Query input for listing all categories. */
    public record ListCategoriesQuery() {}

    @Override
    public List<CategoryView> execute(ListCategoriesQuery query) {
        return categoryRepository.findAll().stream()
                .map(
                        c ->
                                new CategoryView(
                                        c.getCategoryUuid(),
                                        c.getCategoryName(),
                                        c.getDescription()))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
