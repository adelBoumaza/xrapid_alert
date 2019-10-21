package space.xrapid.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import space.xrapid.domain.Exchange;
import space.xrapid.domain.XrpTrade;
import space.xrapid.domain.bx.MessageConverter;
import space.xrapid.domain.bx.Response;
import space.xrapid.domain.bx.Trade;

import javax.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class BxService implements TradeService {

    private RestTemplate restTemplate = new RestTemplate();

    private String apiUrl = "https://bx.in.th/api/trade/?pairing=xrp";

    @PostConstruct
    private void init() {
        //BX.IN.TH send non Json contentType even we set Accept header = Json
        restTemplate.getMessageConverters().add(new MessageConverter());
    }

    @Override
    public List<XrpTrade> fetchTrades(OffsetDateTime begin) {
        HttpEntity<String> entity = getEntity();

        ResponseEntity<Response> response = restTemplate.exchange(apiUrl,
                HttpMethod.GET, entity, Response.class);

        return response.getBody().getTrades().stream()
                .map(this::mapTrade)
                .filter(p -> begin.isBefore(p.getDateTime()))
                .collect(Collectors.toList());
    }


    private XrpTrade mapTrade(Trade trade) {
        //TODO check if exchange using UTC
        OffsetDateTime date = OffsetDateTime.parse(trade.getTradeDate().replace(" ", "T" ) + "+00:00",
                DateTimeFormatter.ISO_DATE_TIME);

        return XrpTrade.builder().amount(Double.valueOf(trade.getAmount()))
                .target(Exchange.BX_IN).timestamp(date.toEpochSecond() * 1000)
                .dateTime(date)
                .orderId(trade.getOrderId())
                .rate(Double.valueOf(trade.getRate()))
                .build();
    }
}