/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.config.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.AirbyteConfig;
import io.airbyte.config.ConfigSchema;
import io.airbyte.validation.json.JsonValidationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class GoogleSecretsManagerConfigPersistence implements ConfigPersistence {

  public GoogleSecretsManagerConfigPersistence() {}

  public String getVersion() {
    return "secrets-v1";
  }

  /**
   * Determines the secrets manager key name for storing a particular config
   */
  protected <T> String generateKeyNameFromType(AirbyteConfig configType, String configId) {
    return String.format("%s-%s-%s-configuration", getVersion(), configType.getIdFieldName(), configId);
  }

  protected <T> String generateKeyPrefixFromType(AirbyteConfig configType) {
    return String.format("%s-%s-", getVersion(), configType.getIdFieldName());
  }

  @Override
  public <T> T getConfig(AirbyteConfig configType, String configId, Class<T> clazz)
      throws ConfigNotFoundException, JsonValidationException, IOException {
    String keyName = generateKeyNameFromType(configType, configId);
    return Jsons.deserialize(GoogleSecretsManager.readSecret(keyName), clazz);
  }

  @Override
  public <T> List<T> listConfigs(AirbyteConfig configType, Class<T> clazz) throws JsonValidationException, IOException {
    List<T> configs = new ArrayList<T>();
    for (String keyName : GoogleSecretsManager.listSecretsMatching(generateKeyPrefixFromType(configType))) {
      configs.add(Jsons.deserialize(GoogleSecretsManager.readSecret(keyName), clazz));
    }
    return configs;
  }

  @Override
  public <T> void writeConfig(AirbyteConfig configType, String configId, T config) throws JsonValidationException, IOException {
    String keyName = generateKeyNameFromType(configType, configId);
    GoogleSecretsManager.saveSecret(keyName, Jsons.serialize(config));
  }

  @Override
  public void deleteConfig(AirbyteConfig configType, String configId) throws ConfigNotFoundException, IOException {
    String keyName = generateKeyNameFromType(configType, configId);
    GoogleSecretsManager.deleteSecret(keyName);
  }

  @Override
  public <T> void replaceAllConfigs(Map<AirbyteConfig, Stream<T>> configs, boolean dryRun) throws IOException {
    if (dryRun) {
      for (final Map.Entry<AirbyteConfig, Stream<T>> configuration : configs.entrySet()) {
        configuration.getValue().forEach(Jsons::serialize);
      }
      return;
    }
    for (final Map.Entry<AirbyteConfig, Stream<T>> configuration : configs.entrySet()) {
      AirbyteConfig configType = configuration.getKey();
      configuration.getValue().forEach(config -> {
        try {
          GoogleSecretsManager.saveSecret(generateKeyNameFromType(configType, configType.getId(config)), Jsons.serialize(config));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }
  }

  @Override
  public Map<String, Stream<JsonNode>> dumpConfigs() throws IOException {
    final Map<String, Stream<JsonNode>> configs = new HashMap<>();

    for (AirbyteConfig ctype : new ConfigSchema[] {ConfigSchema.SOURCE_CONNECTION, ConfigSchema.DESTINATION_CONNECTION}) {
      List<String> names = GoogleSecretsManager.listSecretsMatching(generateKeyPrefixFromType(ctype));
      final List<JsonNode> configList = new ArrayList<JsonNode>();
      for (String name : names) {
        configList.add(Jsons.deserialize(GoogleSecretsManager.readSecret(name), JsonNode.class));
      }
      configs.put(ctype.name(), configList.stream());
    }

    return configs;
  }

}