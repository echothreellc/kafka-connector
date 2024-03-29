/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017-2023 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/main/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.cloud.connectors.kafka.api;

import static java.lang.annotation.ElementType.METHOD;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import javax.enterprise.util.Nonbinding;

/**
 * Annotation to indicate the method to be called on an MDB when a message is
 * received on the specified topics. Use this annotation when yuou want to receive
 * each individual record as a single method call.
 * @see OnRecords
 * If topics is an empty array all topics will trigger the callback
 * @author Steve Millidge (Payara Foundation)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({METHOD})
public @interface OnRecord {
    /**
     * If topics is an empty array all topics will trigger the callback
     * Otherwise this method will only match records sent on the topics specified.
     * 
     * @return A String list of topics from the annotation
    */
    @Nonbinding String[] topics() default {};
    
    /**
     * If set to true other methods on the KafkaListener class will be called
     * if they also match the topic as well as this annotated method
     * If set to false (the default) other methods will not be tested to see if they
     * have matching annotations.
     * @return The value of matchOtherMethods from the annotation
     */
    @Nonbinding boolean matchOtherMethods() default false;
}
