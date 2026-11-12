//+------------------------------------------------------------------+
//| AurumBridge.mq5                                                  |
//| Sends your broker's XAUUSD bid/ask to the AURUM XAU backend so   |
//| the app can compare broker prices with Dukascopy.                |
//|                                                                  |
//| Setup (MetaTrader 5):                                            |
//|  1. Tools > Options > Expert Advisors > "Allow WebRequest for    |
//|     listed URL" and add your backend URL (e.g. https://...).     |
//|  2. Copy this file to MQL5/Experts, compile in MetaEditor.       |
//|  3. Attach to an XAUUSD chart; set BackendUrl and BrokerToken    |
//|     (= AURUM_BROKER_FEED_TOKEN on the backend).                  |
//| It only READS prices. It never places orders.                    |
//+------------------------------------------------------------------+
#property strict
#property version "1.00"

input string BackendUrl   = "https://your-backend.example.com";
input string BrokerToken  = "";
input string BrokerName   = "Exness-MT5";
input int    IntervalSecs = 1;

int OnInit()
{
   if(StringLen(BrokerToken) < 16)
   {
      Print("AurumBridge: BrokerToken missing or too short");
      return(INIT_PARAMETERS_INCORRECT);
   }
   EventSetTimer(MathMax(1, IntervalSecs));
   return(INIT_SUCCEEDED);
}

void OnDeinit(const int reason)
{
   EventKillTimer();
}

void OnTimer()
{
   MqlTick t;
   if(!SymbolInfoTick(_Symbol, t)) return;
   // Broker server time is not necessarily UTC: send the current UTC time of this PC instead.
   long utcMs = (long)TimeGMT() * 1000;
   string body = StringFormat("{\"broker\":\"%s\",\"symbol\":\"%s\",\"bid\":%.3f,\"ask\":%.3f,\"timeMs\":%I64d}",
                              BrokerName, _Symbol, t.bid, t.ask, utcMs);
   char data[];
   StringToCharArray(body, data, 0, StringLen(body), CP_UTF8);
   char result[];
   string headers = "Content-Type: application/json\r\nX-Broker-Token: " + BrokerToken + "\r\n";
   string resultHeaders;
   int code = WebRequest("POST", BackendUrl + "/api/broker/quote", headers, 5000, data, result, resultHeaders);
   if(code != 200 && code != 204)
      PrintFormat("AurumBridge: backend returned %d (error %d)", code, GetLastError());
}
