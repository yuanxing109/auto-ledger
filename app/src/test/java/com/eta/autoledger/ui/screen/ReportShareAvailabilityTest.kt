package com.eta.autoledger.ui.screen

import com.eta.autoledger.data.Bucket
import com.eta.autoledger.data.CategoryAgg
import com.eta.autoledger.data.ReportAggregate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportShareAvailabilityTest {

    @Test
    fun emptyAggregateHasNothingToShare() {
        val aggregate = ReportAggregate()

        assertFalse(aggregate.hasShareableData())
        assertFalse(aggregate.hasShareableChartData())
    }

    @Test
    fun incomeOnlyAggregateCannotShareEmptyExpenseCharts() {
        val aggregate = ReportAggregate(income = 88.0)

        assertTrue(aggregate.hasShareableData())
        assertFalse(aggregate.hasShareableChartData())
    }

    @Test
    fun chartBucketEnablesCurrentChartSharing() {
        val aggregate = ReportAggregate(
            expense = 20.0,
            trend = listOf(Bucket("2026-11-01", 20.0)),
            categories = listOf(CategoryAgg("餐饮", 20.0, 1)),
        )

        assertTrue(aggregate.hasShareableData())
        assertTrue(aggregate.hasShareableChartData())
    }
}
