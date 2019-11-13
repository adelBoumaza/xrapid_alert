package space.xrapid.listener.endtoend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Async;
import space.xrapid.domain.*;
import space.xrapid.domain.ripple.Payment;
import space.xrapid.listener.XrapidCorridors;
import space.xrapid.service.ExchangeToExchangePaymentService;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
public class EndToEndXrapidCorridors extends XrapidCorridors {
    private Exchange destinationExchange;

    private Currency sourceFiat;

    public Exchange getDestinationExchange() {
        return destinationExchange;
    }

    public Currency getSourceFiat() {
        return sourceFiat;
    }

    public EndToEndXrapidCorridors(ExchangeToExchangePaymentService exchangeToExchangePaymentService, SimpMessageSendingOperations messagingTemplate, Exchange destinationExchange, Currency sourceFiat) {
        super(exchangeToExchangePaymentService, messagingTemplate);

        this.sourceFiat = sourceFiat;
        this.destinationExchange = destinationExchange;
    }

    @Async
    public CompletableFuture<List<ExchangeToExchangePayment>> searchXrapidPayments(List<Payment> payments, List<Trade> trades, double rate) {
        this.rate = rate;

        tradesIdAlreadyProcessed = new HashSet<>();

        this.trades = trades;
        return CompletableFuture.completedFuture(submit(payments));
    }

    @Override
    public SpottedAt getSpottedAt() {
        return SpottedAt.SOURCE_AND_DESTINATION;
    }

    @Override
    protected List<ExchangeToExchangePayment> submit(List<Payment> payments) {
        List<Payment> paymentsToProcess = payments.stream()
                .filter(this::isXrapidCandidate).collect(Collectors.toList());

        if (paymentsToProcess.isEmpty()) {
            return new ArrayList<>();
        }

        return paymentsToProcess.stream()
                .map(this::mapPayment)
                .filter(this::fiatToXrpTradesExists)
                .filter(this::xrpToFiatTradesExists)
                .sorted(Comparator.comparing(ExchangeToExchangePayment::getDateTime))
                .peek(this::persistPayment)
                .collect(Collectors.toList());
    }

    @Override
    protected ExchangeToExchangePayment mapPayment(Payment payment) {
        try {
            Exchange source = Exchange.byAddress(payment.getSource());
            Exchange destination = Exchange.byAddress(payment.getDestination());
            boolean xrapidCorridorConfirmed = source.isConfirmed() && destination.isConfirmed();

            OffsetDateTime dateTime = OffsetDateTime.parse(payment.getExecutedTime(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            return ExchangeToExchangePayment.builder()
                    .amount(Double.valueOf(payment.getDeliveredAmount()))
                    .destination(Exchange.byAddress(payment.getDestination()))
                    .source(Exchange.byAddress(payment.getSource(), getSourceFiat()))
                    .sourceAddress(payment.getSource())
                    .destinationAddress(payment.getDestination())
                    .tag(payment.getDestinationTag())
                    .transactionHash(payment.getTxHash())
                    .timestamp(dateTime.toEpochSecond() * 1000)
                    .dateTime(dateTime)
                    .confirmed(xrapidCorridorConfirmed)
                    .spottedAt(getSpottedAt())
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void persistPayment(ExchangeToExchangePayment exchangeToFiatPayment) {
        exchangeToFiatPayment.setUsdValue(exchangeToFiatPayment.getAmount() * rate);
        exchangeToFiatPayment.setDestinationFiat(exchangeToFiatPayment.getDestination().getLocalFiat());
        exchangeToFiatPayment.setSourceFiat(exchangeToFiatPayment.getSource().getLocalFiat());
        if (exchangeToExchangePaymentService.save(exchangeToFiatPayment, false)) {
            notify(exchangeToFiatPayment);
        }
    }

}
