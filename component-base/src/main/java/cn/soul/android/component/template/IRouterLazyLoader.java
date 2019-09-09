package cn.soul.android.component.template;

import java.util.List;

/**
 * @author panxinghai
 * <p>
 * date : 2019-09-04 15:56
 */
public interface IRouterLazyLoader {
    List<IRouterFactory> lazyLoadFactoryByGroup(String group);
}