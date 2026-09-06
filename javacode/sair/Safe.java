package sair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * 线程安全集合工厂。
 * <p>
 * 职责:为框架内部共享集合(LoaderManager 的登记表、SairLoader.mainMap 等)
 * 提供基础同步版本;工厂方法本身由主线程在启动期调用。
 * <p>
 * 线程安全语义:返回的匿名子类只对基础单操作(put/get/add/remove/contains/
 * clear/size/isEmpty/putAll/set)加 synchronized;迭代器遍历、遍历中修改、
 * "查重后写入"等复合操作均不在保护范围内,调用方必须自行在集合对象上同步
 * (例如 LoaderManager.loadExecJar 的 synchronized(execJarPathSet))。
 * <p>
 * 二进制兼容约束(重要,不可改):工厂返回匿名子类但仍声明为
 * HashMap/HashSet/ArrayList 类型,以保证旧 0.5.3 插件反编译引用处(如
 * LoaderManager.ExecLoaders 等公开字段)的字段描述符不变;不得把返回类型
 * 改为 ConcurrentHashMap 等其它类型,也不得把 Safe 改为可实例化形态。
 */
public final class Safe {

	/**
	 * 私有构造:纯静态工具类,禁止实例化。
	 */
	private Safe() {
	}

	/**
	 * 创建基础操作同步的 HashMap 匿名子类(返回类型保持 HashMap 以兼容旧插件,
	 * 字段描述符不变)。
	 *
	 * @param <K> 键类型
	 * @param <V> 值类型
	 * @return 单操作同步的 HashMap;遍历/复合操作需调用方外部同步
	 */
	public static <K, V> HashMap<K, V> map() {
		return new HashMap<K, V>() {
			private static final long serialVersionUID = 1L;

			public synchronized V put(K k, V v) {
				return super.put(k, v);
			}

			public synchronized V remove(Object k) {
				return super.remove(k);
			}

			public synchronized V get(Object k) {
				return super.get(k);
			}

			public synchronized boolean containsKey(Object k) {
				return super.containsKey(k);
			}

			public synchronized boolean containsValue(Object v) {
				return super.containsValue(v);
			}

			public synchronized void clear() {
				super.clear();
			}

			public synchronized void putAll(Map<? extends K, ? extends V> m) {
				super.putAll(m);
			}

			public synchronized boolean isEmpty() {
				return super.isEmpty();
			}

			public synchronized int size() {
				return super.size();
			}
		};
	}

	/**
	 * 创建基础操作同步的 HashSet 匿名子类(返回类型保持 HashSet 以兼容旧插件,
	 * 字段描述符不变)。
	 *
	 * @param <E> 元素类型
	 * @return 单操作同步的 HashSet;遍历/复合操作需调用方外部同步
	 */
	public static <E> HashSet<E> set() {
		return new HashSet<E>() {
			private static final long serialVersionUID = 1L;

			public synchronized boolean add(E e) {
				return super.add(e);
			}

			public synchronized boolean remove(Object o) {
				return super.remove(o);
			}

			public synchronized boolean contains(Object o) {
				return super.contains(o);
			}

			public synchronized void clear() {
				super.clear();
			}

			public synchronized boolean isEmpty() {
				return super.isEmpty();
			}

			public synchronized int size() {
				return super.size();
			}
		};
	}

	/**
	 * 创建基础操作同步的 ArrayList 匿名子类(返回类型保持 ArrayList 以兼容旧插件,
	 * 字段描述符不变)。
	 *
	 * @param <E> 元素类型
	 * @return 单操作同步的 ArrayList;遍历/复合操作需调用方外部同步
	 */
	public static <E> ArrayList<E> list() {
		return new ArrayList<E>() {
			private static final long serialVersionUID = 1L;

			public synchronized boolean add(E e) {
				return super.add(e);
			}

			public synchronized boolean remove(Object o) {
				return super.remove(o);
			}

			public synchronized E get(int i) {
				return super.get(i);
			}

			public synchronized E set(int i, E e) {
				return super.set(i, e);
			}

			public synchronized E remove(int i) {
				return super.remove(i);
			}

			public synchronized int size() {
				return super.size();
			}

			public synchronized boolean isEmpty() {
				return super.isEmpty();
			}

			public synchronized void clear() {
				super.clear();
			}
		};
	}
}
