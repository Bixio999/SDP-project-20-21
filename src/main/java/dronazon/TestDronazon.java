package dronazon;

import java.sql.Timestamp;
import java.util.Scanner;

import droneNetwork.proto.DroneNetworkServiceOuterClass.Delivery;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;


/**
 * Test class to verify if Dronazon publisher correctly generate the orders.
 *
 * The TestDronazon class is a simple MQTT subscriber client that prints each message it reads from the topic.
 */
public class TestDronazon {
    public static void main(String[] args) {
        MqttClient client;
        String broker = "tcp://localhost:1883";
        String clientId = MqttClient.generateClientId();
        String topic = "dronazon/smartcity/orders";
        int qos = 1;

        try {
            client = new MqttClient(broker, clientId);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);

            // Connect the client
            System.out.println(clientId + " Connecting Broker " + broker);
            client.connect(options);
            System.out.println(clientId + " Connected");

            client.setCallback(new MqttCallback(){

                @Override
                public void connectionLost(Throwable cause) {
                    System.out.println(clientId + " Connection lost! cause:" + cause.getMessage() + cause.getCause());
                    
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    String time = new Timestamp(System.currentTimeMillis()).toString();

                    Delivery delivery = Delivery.parseFrom(message.getPayload());
                    System.out.println(clientId + " received an order! " + 
                            "\n\tTime: " + time + 
                            "\n\tOrder: " + delivery.toString()
                        );  
                    System.out.println("\n ***  Press a random key to exit *** \n");
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // not used here
                    
                }
            });

            System.out.println(clientId + " Subscribing ... - Thread PID: " + Thread.currentThread().getId());
            client.subscribe(topic,qos);
            System.out.println(clientId + " Subscribed to topics : " + topic);


            System.out.println("\n ***  Press a random key to exit *** \n");
            Scanner command = new Scanner(System.in);
            command.nextLine();
            client.disconnect();
            command.close();
        } 
        catch (MqttException me ) {
            System.out.println("reason " + me.getReasonCode());
            System.out.println("msg " + me.getMessage());
            System.out.println("loc " + me.getLocalizedMessage());
            System.out.println("cause " + me.getCause());
            System.out.println("excep " + me);
            me.printStackTrace();
        }
        catch (Exception e) {
            e.printStackTrace();
        }

    }
}
