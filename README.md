Data generator
==============
Maven plugin to generate data classes from a .yaml definition file.

It can be also executed standalone.

The data is serializable and upgradable from any version to the latest.

The transformations between each version are defined in the .yaml file itself.

Supports custom (external) data types, custom data arrays, custom data optionals, interfaces with common getters / setters.

The serialized data is very lightweight: it serializes only the data, without any metadata or type specification, because it's all deducted on compile-time from the definitions file.
