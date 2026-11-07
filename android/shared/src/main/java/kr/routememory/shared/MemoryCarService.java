package kr.routememory.shared;

import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.car.app.*;
import androidx.car.app.validation.HostValidator;
import kr.routememory.core.RoadRoute;

public final class MemoryCarService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        if(BuildConfig.DEBUG)return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build();
    }
    @NonNull @Override public Session onCreateSession() {
        return new Session(){
            private Screen root;
            @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
                if("androidx.car.app.action.NAVIGATE".equals(intent.getAction())) configureDestination(intent);
                root=new RoadCarScreen(getCarContext());
                return root;
            }
            @Override public void onNewIntent(@NonNull Intent intent) { handle(intent); }
            private void handle(Intent intent) {
                if("androidx.car.app.action.NAVIGATE".equals(intent.getAction())) {
                    configureDestination(intent);
                    DemoController.get(getCarContext()).stop();
                    // The map is always the root. Reuse it instead of installing a second surface owner.
                    getCarContext().getCarService(ScreenManager.class).popToRoot();
                }
            }
            private void configureDestination(Intent intent) {
                RoadRoute.Point destination=RoadIntents.destination(intent);
                if(destination!=null)RoadController.get(getCarContext()).setDestination(destination);
                else CarToast.makeText(getCarContext(),"요청한 좌표를 읽지 못했습니다. 목적지 검색에서 주소나 장소를 찾아주세요",CarToast.LENGTH_LONG).show();
            }
        };
    }
}
