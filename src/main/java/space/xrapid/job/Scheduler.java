package space.xrapid.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import space.xrapid.domain.Currency;
import space.xrapid.domain.Exchange;
import space.xrapid.domain.Stats;
import space.xrapid.domain.Trade;
import space.xrapid.domain.ripple.Payment;
import space.xrapid.listener.endtoend.EndToEndXrapidCorridors;
import space.xrapid.listener.inbound.InboundXrapidCorridors;
import space.xrapid.listener.outbound.OutboundXrapidCorridors;
import space.xrapid.service.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@EnableScheduling
@Component
public class Scheduler {

    @Autowired
    private List<TradeService> tradeServices;

    @Autowired
    private XrpLedgerService xrpLedgerService;

    @Autowired
    private ExchangeToExchangePaymentService exchangeToExchangePaymentService;

    @Autowired
    private XrapidInboundAddressService xrapidInboundAddressService;


    @Autowired
    protected SimpMessageSendingOperations messagingTemplate;

    @Autowired
    private RateService rateService;

    public static Set<String> transactionHashes = new HashSet<>();


    private OffsetDateTime lastWindowEnd;
    private OffsetDateTime windowStart;
    private OffsetDateTime windowEnd;

    @Scheduled(fixedDelay = 30000)
    public void odl() throws Exception {

        OffsetDateTime lastWindowEndRollback = lastWindowEnd;
        OffsetDateTime windowStartRollback = windowStart;
        OffsetDateTime windowEndRollback = windowEnd;

        List<Exchange> allConfirmedExchange = Stream.of(Exchange.values()).collect(Collectors.toList());
        List<Exchange> availableExchangesWithApi = tradeServices.stream().map(TradeService::getExchange).collect(Collectors.toList());

        Set<Currency> destinationFiats = availableExchangesWithApi.stream().map(Exchange::getLocalFiat).collect(Collectors.toSet());

        try {
            updatePaymentsWindows();

            List<Trade> allTrades = new ArrayList<>();

            tradeServices.stream()
                    .filter(service -> service.getExchange().isConfirmed())
                    .forEach(tradeService -> {
                        try {
                            List<Trade> trades = tradeService.fetchTrades(windowEnd.minusMinutes(5 + 2 + 5));
                            allTrades.addAll(trades);
                            log.info("{} trades fetched from {} from {}", trades.size(), tradeService.getExchange(), windowEnd.minusMinutes(5 + 2 + 5));
                        } catch (Exception e) {
                            log.error("Error fetching {} trades", tradeService.getExchange());
                        }
                    });

            double rate = rateService.getXrpUsdRate();

            log.info("Fetching payments from XRP Ledger from {} to {}", windowEnd.minusMinutes(5 + 2), windowEnd.minusMinutes(5));
            List<Payment> payments = xrpLedgerService.fetchPayments(windowEnd.minusMinutes(5 + 2), windowEnd.minusMinutes(5));

            log.info("{} payments fetched from XRP Ledger", payments.size());

            log.info("Scan all XRPL TRX between exchanges that providing API");
            destinationFiats.forEach(fiat -> {
            availableExchangesWithApi.stream()
                        .filter(exchange -> !exchange.getLocalFiat().equals(fiat))
                        .forEach(exchange -> {
                            final Set<String> tradeIds = new HashSet<>();
                            Arrays.asList(30, 60, 120, 300).forEach(delta -> {
                                new EndToEndXrapidCorridors(exchangeToExchangePaymentService, xrapidInboundAddressService, messagingTemplate, exchange, fiat, delta, delta, true, tradeIds)
                                        .searchXrapidPayments(payments, allTrades, rate);
                            });
                        });
            });

            log.info("Search all XRPL TRX between all exchanges, that are followed by a sell in the local currency (in case source exchange not providing API)");
            availableExchangesWithApi.forEach(exchange -> {
                new InboundXrapidCorridors(exchangeToExchangePaymentService, messagingTemplate, exchange, availableExchangesWithApi).searchXrapidPayments(payments, allTrades.stream().filter(trade -> trade.getExchange().equals(exchange)).collect(Collectors.toList()), rate);
            });

            log.info("Search for all XRPL TRX from exchanges with API to all exchanes (in case destination exchange not providing API)");
            allConfirmedExchange.stream()
                    .filter(exchange -> !availableExchangesWithApi.contains(exchange))
                    .forEach(exchange -> {
                        new OutboundXrapidCorridors(exchangeToExchangePaymentService, messagingTemplate, exchange, availableExchangesWithApi).searchXrapidPayments(payments, allTrades, rate);
                    });


            destinationFiats.forEach(fiat -> {
                availableExchangesWithApi.stream()
                        .filter(exchange -> !exchange.getLocalFiat().equals(fiat))
                        .forEach(exchange -> {
                            new EndToEndXrapidCorridors(exchangeToExchangePaymentService, xrapidInboundAddressService, messagingTemplate, exchange, fiat, 60, 60, false, null)
                                    .searchXrapidPayments(payments, allTrades, rate);
                        });
            });


            Stats stats = exchangeToExchangePaymentService.calculateStats();

            if (stats != null) {
                messagingTemplate.convertAndSend("/topic/stats", exchangeToExchangePaymentService.calculateStats());
            }

        } catch (Exception e) {
            log.error("", e);
            lastWindowEnd = lastWindowEndRollback;
            windowStart = windowStartRollback;
            windowEnd = windowEndRollback;

            Thread.sleep(30000);
        }

    }

    private void updatePaymentsWindows() {
        windowEnd = OffsetDateTime.now(ZoneOffset.UTC);
        windowStart = windowEnd.minusMinutes(20);

        if (lastWindowEnd != null) {
            windowStart = lastWindowEnd;
        }

        lastWindowEnd = windowEnd;
    }
}
