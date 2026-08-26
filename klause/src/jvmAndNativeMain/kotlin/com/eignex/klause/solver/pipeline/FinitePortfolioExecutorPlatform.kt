package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioWorker

internal actual fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor = Portfolio(workers)
