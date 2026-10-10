package com.financial.news.controller;

import com.financial.news.common.Result;
import com.financial.news.dto.response.quote.HotListVO;
import com.financial.news.dto.response.quote.IndexListVO;
import com.financial.news.dto.response.quote.QuoteKlineVO;
import com.financial.news.dto.response.quote.QuoteSearchVO;
import com.financial.news.dto.response.quote.QuoteSnapshotVO;
import com.financial.news.dto.response.quote.QuoteTrendVO;
import com.financial.news.dto.response.quote.StockListVO;
import com.financial.news.service.quote.QuoteSearchService;
import com.financial.news.service.quote.QuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行情控制器（公开只读，限流见 QuoteRateLimitInterceptor）
 * <p>type ∈ stock|index 与 symbol 共同构成标的完整标识，不得只按代码跳转（PRD 5.2-5）</p>
 */
@Tag(name = "行情模块", description = "指数卡、热门标的、标的搜索、快照/分时/日K")
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;
    private final QuoteSearchService quoteSearchService;

    @Operation(summary = "全部股票列表（按市场分页，含当前页行情）")
    @GetMapping("/stocks")
    public Result<StockListVO> stocks(@RequestParam String market,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "50") Integer pageSize) {
        return Result.ok(quoteService.getStockList(market, page, pageSize));
    }

    @Operation(summary = "指数卡片（单市场批量快照）")
    @GetMapping("/indices")
    public Result<IndexListVO> indices(@RequestParam String market) {
        return Result.ok(quoteService.getIndexCards(market));
    }

    @Operation(summary = "热门标的列表（单市场）")
    @GetMapping("/hot")
    public Result<HotListVO> hot(@RequestParam String market,
                                 @RequestParam(required = false) Integer limit) {
        return Result.ok(quoteService.getHotList(market, limit));
    }

    @Operation(summary = "标的搜索（代码/名称/拼音首字母，三市场混合）")
    @GetMapping("/search")
    public Result<QuoteSearchVO> search(@RequestParam String keyword,
                                        @RequestParam(required = false, defaultValue = "10") Integer limit) {
        return Result.ok(quoteSearchService.search(keyword, Math.min(Math.max(limit, 1), 30)));
    }

    @Operation(summary = "单标的实时快照（报价区）")
    @GetMapping("/{type}/{symbol}/snapshot")
    public Result<QuoteSnapshotVO> snapshot(@PathVariable String type, @PathVariable String symbol) {
        return Result.ok(quoteService.getSnapshot(type, symbol));
    }

    @Operation(summary = "单标的当日分时（价格线/均价线/昨收基准）")
    @GetMapping("/{type}/{symbol}/trend")
    public Result<QuoteTrendVO> trend(@PathVariable String type, @PathVariable String symbol) {
        return Result.ok(quoteService.getTrend(type, symbol));
    }

    @Operation(summary = "单标的日 K（前复权 + MA5/10/20/60）")
    @GetMapping("/{type}/{symbol}/kline")
    public Result<QuoteKlineVO> kline(@PathVariable String type, @PathVariable String symbol,
                                      @RequestParam(required = false) Integer count) {
        return Result.ok(quoteService.getKline(type, symbol, count));
    }
}
