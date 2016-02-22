(ns terraboot.vpc
  (:require [terraboot.core :refer :all]
            [clojure.string :as string]))

(def vpc-name "sandpit")

(def vpc-cidr-block "172.20.0.0/20")

(defn cidr-start
  [cidr-block]
  (first (string/split cidr-block #"/")))

(defn parse-ip
  [ipv4]
  (mapv #(Integer/parseInt %) (string/split ipv4 #"\.")))

(defn reconstitute-ip
  [ip-map]
  (string/join "." ip-map))

(defn fallback-dns
  "this assumes dns is always 2 up from start of range"
  [cidr-block]
  (let [parsed-ip (parse-ip (cidr-start cidr-block))
        second (+ 2 (last parsed-ip))]
    (reconstitute-ip (conj (vec (drop-last parsed-ip)) second))))

(def subnet-types [:public :private])

(def cidr-block {:public {:a "172.20.0.0/24"
                          :b "172.20.1.0/24"
                          :c "172.20.2.0/24"}
                 :private {:a "172.20.8.0/24"
                           :b "172.20.9.0/24"
                           :c "172.20.10.0/24"}
                 })

(defn add-to-every-value-map
  [map key value]
  (reduce-kv (fn [m k v]
               (assoc m k (assoc v key value))) {} map))

(defn in-vpc
  [vpc-name & resources]
  (let [vpc-id (id-of "aws_vpc" vpc-name)]
    (apply merge-in
           (map
            (apply comp
                       (map #(partial (fn [type resource] (update-in resource [:resource type] (fn [spec] (add-to-every-value-map spec :vpc_id vpc-id)))) %)
                           ["aws_security_group"
                            "aws_internet_gateway"
                            "aws_subnet"
                            "aws_route_table"]))
                resources))))

(def vpc-vpn-infra
  (merge-in
            (resource "aws_vpc" vpc-name
                      {:tags {:Name vpc-name}
                       :cidr_block vpc-cidr-block})


            (in-vpc vpc-name
                    (aws-instance "vpn" {
                                         :user_data (from-template "vpn-config" {:range-start (cidr-start vpc-cidr-block)
                                                                                 :fallback-dns (fallback-dns vpc-cidr-block)
                                                                                 :ta-key (snippet "vpn-keys/ta.key")
                                                                                 :ca-cert (snippet "vpn-keys/ca.crt")
                                                                                 :vpn-key (snippet "vpn-keys/mesos-vpn-gw.key")
                                                                                 :vpn-cert (snippet "vpn-keys/mesos-vpn-gw.crt")
                                                                                 :dh-param (snippet "vpn-keys/dh2048.pem")})
                                         :subnet_id (id-of "aws_subnet" "public-b")
                                         :ami "ami-bc5b48d0"
                                         :vpc_security_group_ids [(id-of "aws_security_group" "vpn")
                                                                  (id-of "aws_security_group" "allow_outbound")
                                                                  ]
                                         :associate_public_ip_address true
                                         })
                    (security-group "allow_outbound" {}
                                    {:type "egress"
                                     :from_port 0
                                     :to_port 0
                                     :protocol -1
                                     :cidr_blocks [all-external]
                                     })

                    (security-group "allow_external_http_https" {}
                                    {:from_port 80
                                     :to_port 80
                                     :cidr_blocks [all-external]
                                     }
                                    {:from_port 443
                                     :to_port 443
                                     :cidr_blocks [all-external]})

                    (security-group "vpn" {}
                                    {:from_port 22
                                     :to_port 22
                                     :cidr_blocks [all-external]}
                                    {:from_port 1194
                                     :to_port 1194
                                     :protocol "udp"
                                     :cidr_blocks [all-external]})

                    (security-group "allow-all-tcp-within-public-subnet" {}
                                    {:from_port 0
                                     :to_port 65535
                                     :cidr_blocks (vals (:public cidr-block))})

                    (security-group "allow-all-udp-within-public-subnet" {}
                                    {:from_port 0
                                     :to_port 65535
                                     :protocol "udp"
                                     :cidr_blocks (vals (:public cidr-block))})

                    (security-group "allow-icmp-within-public-subnet" {}
                                    {:from_port 0
                                     :to_port 65535
                                     :protocol "udp"
                                     :cidr_blocks (vals (:public cidr-block))})

                    (resource "aws_internet_gateway" vpc-name
                              {:tags {:Name "main"}})

                    (resource "aws_route_table" "public" {:tags { :Name "public"}
                                                          :route { :cidr_block all-external
                                                                  :gateway_id (id-of "aws_internet_gateway" vpc-name)}
                                                          :vpc_id (id-of "aws_vpc" vpc-name)})

                    ;; Public Subnets
                    (resource-seq
                     (apply concat
                            (for [az azs]
                              (let [subnet-name (stringify "public" "-" az)
                                    nat-eip (stringify subnet-name "-nat")]
                                [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                            :cidr_block (get-in cidr-block [:public az])
                                                            :availability_zone (stringify region az)
                                                            }]
                                 ["aws_route_table_association" subnet-name {:route_table_id (id-of "aws_route_table" "public")
                                                                             :subnet_id (id-of "aws_subnet" subnet-name)
                                                                             }]
                                 ["aws_nat_gateway" subnet-name {:allocation_id (id-of "aws_eip" nat-eip)
                                                                 :subnet_id  (id-of "aws_subnet" subnet-name)}]

                                 ["aws_eip" nat-eip {:vpc true}]])
                              )))
                    ;; Private Subnets

                    (resource-seq
                     (apply concat
                            (for [az azs]
                              (let [subnet-name (stringify "private-" az)
                                    public-subnet-name (stringify "public-" az)]
                                [["aws_subnet" subnet-name {:tags {:Name subnet-name}
                                                            :cidr_block (get-in cidr-block [:private az])
                                                            :availability_zone (stringify region az)
                                                            }]
                                 ["aws_route_table" subnet-name {:tags {:Name subnet-name}
                                                                 :route {:cidr_block all-external
                                                                         :nat_gateway_id (id-of "aws_nat_gateway" public-subnet-name)}}

                                  ]
                                 ["aws_route_table_association" subnet-name {:route_table_id (id-of "aws_route_table" subnet-name)
                                                                             :subnet_id (id-of "aws_subnet" subnet-name)
                                                                             }]])))))))
