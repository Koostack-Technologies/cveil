package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.CategoryView;
import java.util.Optional;

/**
 * Query actuator that retrieves a single category by name.
 */
public class GetCategoryQueryActuator
        implements QueryActuator<
                GetCategoryQueryActuator.GetCategoryQuery, Optional<CategoryView>> {

    private final CategoryRepository categoryRepository;

    public GetCategoryQueryActuator(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Query input for retrieving a single category.
     *
     * @param categoryName the category name to look up
     */
    public record GetCategoryQuery(String categoryName) {}

    @Override
    public Optional<CategoryView> execute(GetCategoryQuery query) {
        return categoryRepository
                .findByName(query.categoryName())
                .map(
                        c ->
                                new CategoryView(
                                        c.getCategoryId(),
                                        c.getCategoryName(),
                                        c.getDescription()));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
