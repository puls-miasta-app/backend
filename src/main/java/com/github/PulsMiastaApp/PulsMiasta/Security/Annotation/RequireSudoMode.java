package com.github.PulsMiastaApp.PulsMiasta.Security.Annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireSudoMode {
}
