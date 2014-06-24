(function () {
  goog.provide('gn_layermanager_directive');

  var module = angular.module('gn_layermanager_directive', [
  ]);

  /**
   * @ngdoc filter
   * @name gn_wmsimport_directive.filter:gnReverse
   *
   * @description
   * Filter for the gnLayermanager directive's ngRepeat. The filter
   * reverses the array of layers so layers in the layer manager UI
   * have the same order as in the map.
   */
  module.filter('gnReverse', function() {
    return function(items) {
      return items.slice().reverse();
    };
  });

  /**
   * @ngdoc directive
   * @name gn_wmsimport_directive.directive:gnWmsImport
   *
   * @description
   * Panel to load WMS capabilities service and pick layers.
   * The server list is given in global properties.
   */
  module.directive('gnLayermanager', [
    'gnLayerFilters',
    function (gnLayerFilters) {
    return {
      restrict: 'A',
      templateUrl: '../../catalog/components/viewer/layermanager/' +
        'partials/layermanager.html',
      scope: {
        map: '=gnLayermanagerMap'
      },
      link: function (scope, element, attrs) {

        scope.layers = scope.map.getLayers().getArray();
        scope.layerFilterFn = gnLayerFilters.selected;

        scope.removeLayerFromMap = function(layer) {
          scope.map.removeLayer(layer);
        };

        scope.moveLayer = function(layer, delta) {
          var index = scope.layers.indexOf(layer);
          var layersCollection = scope.map.getLayers();
          layersCollection.removeAt(index);
          layersCollection.insertAt(index + delta, layer);
        };
      }
    };
  }]);

})();
