package org.worker.litespring.v1;

import org.worker.litespring.v1.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

public class HnDispatchServlet extends HttpServlet {
    private Properties contextConfig = new Properties();
    //享元模式
    private List<String> classNames = new ArrayList<>();
    private Map<String, Object> ioc = new HashMap<>();
    private Map<String, Method> handlerMapping = new HashMap<>();

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //委派模式
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().println("500 exception, detail: " + e.getMessage());
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        if(!handlerMapping.containsKey(url)) {
            PrintWriter respWriter = resp.getWriter();
            respWriter.println("404 Not Found");
            return ;
        }
        Map<String,String[]> params = req.getParameterMap();
        Method method = handlerMapping.get(url);
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] paramValues = new Object[parameterTypes.length];
        for(int i = 0; i < parameterTypes.length; i++) {
            Class paramterType = parameterTypes[i];
            if(paramterType == HttpServletRequest.class){
                paramValues[i] = req;
            }else if(paramterType == HttpServletResponse.class){
                paramValues[i] = resp;
            }else if(paramterType == String.class){
                //通过运行时的状态去拿
                Annotation[] [] pa = method.getParameterAnnotations();
                for (int j = 0; j < pa.length ; j ++) {
                    for(Annotation a : pa[i]){
                        if(a instanceof HnRequestParam){
                            String paramName = ((HnRequestParam) a).value();
                            if(!"".equals(paramName.trim())){
                                String value = Arrays.toString(params.get(paramName))
                                        .replaceAll("\\[|\\]","")
                                        .replaceAll("\\s+",",");
                                paramValues[i] = value;
                            }
                        }
                    }
                }

            }
        }
        try {
            method.invoke(ioc.get(getBeanName(method.getDeclaringClass())), paramValues);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        //1. 加载配置文件
        doLoadContextConfig(config.getInitParameter("contextConfigLocation"));
        //2. 扫描相关的类
        doScanClasses(contextConfig.get("scan-package").toString());
        //3. 初始化IoC容器
        initIoC();
        //4. 进行DI
        execDI();
        //5. 初始化HandlerMapping
        initHandlerMapping();

        System.out.println("=============Spring init finish==============");
    }

    /**
     * 映射
     */
    private void initHandlerMapping() {
        if(ioc.isEmpty()) {return;}

        for(Map.Entry<String, Object> entry : ioc.entrySet()) {
            if(!entry.getValue().getClass().isAnnotationPresent(HnController.class)) {
                continue;
            }
            String baseUrl = entry.getValue().getClass().getAnnotation(HnController.class).value();

            for(Method m : entry.getValue().getClass().getMethods()) {
                if(!m.isAnnotationPresent(HnRequestMapping.class)) {continue;}
                String url = ("/" + baseUrl + "/" + m.getAnnotation(HnRequestMapping.class).value()).replaceAll("/+", "/");
                handlerMapping.put(url, m);
            }

        }
    }

    /**
     * 对IoC容器对象进行依赖注入
     */
    private void execDI() {
        if(ioc.isEmpty()) {return;}
        for(Map.Entry<String, Object> entry : ioc.entrySet()) {
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for(Field f : fields) {
                if(!f.isAnnotationPresent(HnAutowired.class)) {continue;}
                f.setAccessible(true);
                String beanName = f.getAnnotation(HnAutowired.class).value();
                if("".equals(beanName)) {
                    beanName = getBeanName(f.getDeclaringClass());
                }
                if("".equals(beanName) && f.getClass().isInterface()) {
                    beanName = f.getClass().getName();
                }
                try {
                    f.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 初始化IoC容器
     */
    private void initIoC() {
        if(classNames.isEmpty()) {return ;}
        for(String className : classNames) {
            try {
                Class<?> aClass = Class.forName(className);
                if(aClass.isAnnotationPresent(HnController.class)) {
                    ioc.put(getBeanName(aClass), aClass.newInstance());
                }else if(aClass.isAnnotationPresent(HnService.class)) {
                    String beanName = aClass.getAnnotation(HnService.class).value();
                    if("".equals(beanName)) {
                        beanName = getBeanName(aClass);
                    }
                    Object instance = aClass.newInstance();
                    ioc.put(beanName, instance);
                    for(Class<?> inter : aClass.getInterfaces()) {
                        if(ioc.containsKey(inter.getSimpleName())) {
                            throw new RuntimeException("The" + inter.getName() + "is exists!");
                        }
                        ioc.put(inter.getName(), instance);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String getBeanName(Class<?> aClass) {
        char[] chars = aClass.getSimpleName().toCharArray();
        chars[0] += 32;
        return new String(chars);
    }

    /**
     * 扫描相关类
     * @param scanPackage
     */
    private void doScanClasses(String scanPackage) {
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File file = new File(url.getFile());
        for(File f : file.listFiles()) {
            if(f.isDirectory()) {
                doScanClasses(scanPackage + "." + f.getName());
            }else {
                if(!f.getName().endsWith(".class")) continue;
                classNames.add(scanPackage + "." + f.getName().replace(".class", ""));
            }
        }
    }

    /**
     * 加载配置文件
     * @param contextConfigLocation
     */
    private void doLoadContextConfig(String contextConfigLocation) {
        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
