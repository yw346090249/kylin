/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package org.apache.kylin.engine.spark;

import java.io.File;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.CliCommandExecutor;
import org.apache.kylin.common.util.Logger;
import org.apache.kylin.job.exception.ExecuteException;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableContext;
import org.apache.kylin.job.execution.ExecuteResult;
import org.slf4j.LoggerFactory;

/**
 */
public class SparkExecutable extends AbstractExecutable {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SparkExecutable.class);

    private static final String CLASS_NAME = "className";
    private static final String JARS = "jars";

    public void setClassName(String className) {
        this.setParam(CLASS_NAME, className);
    }

    public void setJars(String jars) {
        this.setParam(JARS, jars);
    }

    private String formatArgs() {
        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : getParams().entrySet()) {
            StringBuilder tmp = new StringBuilder();
            tmp.append("-").append(entry.getKey()).append(" ").append(entry.getValue()).append(" ");
            if (entry.getKey().equals(CLASS_NAME)) {
                stringBuilder.insert(0, tmp);
            } else if (entry.getKey().equals(JARS)) {
                // JARS is for spark-submit, not for app
                continue;
            } else {
                stringBuilder.append(tmp);
            }
        }
        if (stringBuilder.length() > 0) {
            return stringBuilder.substring(0, stringBuilder.length() - 1).toString();
        } else {
            return StringUtils.EMPTY;
        }
    }

    @Override
    protected ExecuteResult doWork(ExecutableContext context) throws ExecuteException {
        final KylinConfig config = context.getConfig();
        if (config.getSparkHome() == null) {
            throw new NullPointerException();
        }
        if (config.getKylinJobJarPath() == null) {
            throw new NullPointerException();
        }
        String jars = this.getParam(JARS);

        String hadoopConf = "/etc/hadoop/conf";
        if (StringUtils.isNotEmpty(config.getHadoopConfDir())) {
            hadoopConf = config.getHadoopConfDir();
        } else {
            String hiveConf = ClassLoader.getSystemClassLoader().getResource("hive-site.xml").getFile().toString();
            File hiveConfFile = new File(hiveConf);
            if (hiveConfFile.exists() == true) {
                logger.info("Locate hive-site.xml in " + hiveConfFile);
                hadoopConf = hiveConfFile.getParent();
            }
        }
        logger.info("Using " + hadoopConf + " as HADOOP_CONF_DIR");

        String hbaseConf = ClassLoader.getSystemClassLoader().getResource("hbase-site.xml").getFile().toString();
        logger.info("Get hbase-site.xml location from classpath: " + hbaseConf);
        File hbaseConfFile = new File(hbaseConf);
        if (hbaseConfFile.exists() == false) {
            throw new IllegalArgumentException("Couldn't find hbase-site.xml from classpath.");
        }

        String jobJar = config.getKylinJobJarPath();
        if (StringUtils.isEmpty(jars)) {
            jars = jobJar;
        }

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("export HADOOP_CONF_DIR=%s && %s/bin/spark-submit --class org.apache.kylin.engine.spark.util.SparkEntry ");

        Map<String, String> sparkConfs = config.getSparkConfigOverride();
        for (Map.Entry<String, String> entry : sparkConfs.entrySet()) {
            stringBuilder.append(" --conf ").append(entry.getKey()).append("=").append(entry.getValue()).append(" ");
        }

        stringBuilder.append("--files %s --jars %s %s %s");
        try {
            String cmd = String.format(stringBuilder.toString(),
                    hadoopConf, config.getSparkHome(), hbaseConfFile.getAbsolutePath(), jars, jobJar, formatArgs());
            logger.info("cmd:" + cmd);
            final StringBuilder output = new StringBuilder();
            CliCommandExecutor exec = new CliCommandExecutor();
            exec.execute(cmd, new Logger() {
                @Override
                public void log(String message) {
                    output.append(message);
                    output.append("\n");
                    logger.info(message);
                }
            });
            return new ExecuteResult(ExecuteResult.State.SUCCEED, output.toString());
        } catch (Exception e) {
            logger.error("error run spark job:", e);
            return new ExecuteResult(ExecuteResult.State.ERROR, e.getLocalizedMessage());
        }
    }

}
