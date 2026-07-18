Java.perform(function () {
    console.log("TAGGGGG");
    var actionDownTime;    
    var MediaClass = Java.use("com.qinggan.keymanager.service.engine.KeyManagerReader");
    MediaClass.onKeyEvent.implementation = function (keyEvent) {
        if (keyEvent.getKeyCode() != 3090) {
            var result = this.onKeyEvent(keyEvent);
            console.log('ANOTHER KEY');
            return result;
        }
        if (keyEvent.getAction() == 0) {
            actionDownTime = new Date();
        }    
        if (keyEvent.getAction() == 1) {
            const Intent = Java.use("android.content.Intent");
            const ActivityThread = Java.use('android.app.ActivityThread');

            var actionUpDownDelta = (new Date()-actionDownTime);
            console.log("####" + actionUpDownDelta.toString());

            if(actionUpDownDelta < 800) {    
                const intent = Intent.$new("android.intent.action.KEYCODE_SWC_USER_DEFINE");

                const packageName = "ru.big.town.anative";
                const className = "ru.big.town.anative.SetModesReceiverDynamic";
                intent.setClassName(packageName, className);
                //intent.putExtra("my_key", "Hello from Frida!");
                const context = ActivityThread.currentApplication().getApplicationContext();
                context.sendBroadcast(intent);
                console.log("[*] Short");
            } else {
                const intent = Intent.$new();
                intent.setClassName("ru.big.town.restoremode", "ru.big.town.restoremode.MainActivity");
                // Флаг обязателен для запуска Активити из не-Activity контекста (из сервиса)
                intent.addFlags(0x10000000); // Intent.FLAG_ACTIVITY_NEW_TASK
                const context = ActivityThread.currentApplication().getApplicationContext();
                context.startActivity(intent);
                console.log("[*] Long");
            }

        
        }
        console.log(keyEvent);
        console.log(keyEvent.getKeyCode());
        console.log(keyEvent.getAction());
        return true;
    };
});

