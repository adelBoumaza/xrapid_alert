import {Component, OnInit} from "@angular/core";
import {HttpClient} from '@angular/common/http';
import {CookieService} from 'ngx-cookie-service';
import {TablesService} from "../tables/tables.service";
import {NgxSpinnerService} from "ngx-spinner";
import * as SockJS from "sockjs-client";
import * as Stomp from 'stompjs';


@Component({
    selector: "app-api",
    templateUrl: "api.component.html"
})
export class ApiComponent implements OnInit {

    qrCodeUrl: string;
    paymentId: string;
    apiKey: ApiKey = null;
    days: Number = 365;
    renew: boolean;
    key:string = '';
    private recInterval = null;

    init: string = null;

    socketCode = '' +
        '       let socket = new SockJS(\'https://api.utility-scan.com/websocket\');\n' +
        '         let client = Stomp.over(socket);\n' +
        '         client.connect({}, function () {\n' +
        '                client.subscribe(\'/top/odl\', function (odlPayment) {\n' +
        '                    console.log(odlPayment);\n' +
        '                }, {apiKey: "test"})\n' +
        '            }\n' +
        '         );'


    constructor(private httpClient: HttpClient, private cookieService: CookieService, private tablesService: TablesService, private spinner: NgxSpinnerService) {


    }

    requestKey() {
        console.log(this.days);
        this.spinner.show();

        let url  = this.renew ? '/api/xumm?days=' + this.days + '&key=' + this.key : '/api/xumm?days=' + this.days;

        this.httpClient.get<PaymentRequestInformation>(url).subscribe(data => {
            this.qrCodeUrl = data.qrCodeUrl;
            this.paymentId = data.paymentId;

            this.spinner.hide();

            this.recInterval = setInterval(() => {
                this.httpClient.get<ApiKey>('/api/xumm/' + data.paymentId).subscribe(apiKey => {
                    if (apiKey.key != "REJECTED" && apiKey.key != "WAITING") {
                        this.apiKey = apiKey;
                        clearInterval(this.recInterval);
                    } else if (apiKey.key == "REJECTED") {
                        this.apiKey = apiKey;
                        clearInterval(this.recInterval);
                    } else {
                        //TODO
                    }
                })
            }, 2000);

        }, error => {
            console.log("errrrrr");
            console.log(error);
        });
    }

    ngOnInit() {
    }


}

export class PaymentRequestInformation {
    paymentId: string;
    qrCodeUrl: string;
}

export class ApiKey {
    expiration: string;
    key: string;
}